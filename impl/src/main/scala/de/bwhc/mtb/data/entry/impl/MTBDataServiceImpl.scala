package de.bwhc.mtb.data.entry.impl


import java.time.Instant
import scala.util._
import scala.concurrent.{
  ExecutionContext,
  Future
}
import cats.data.NonEmptyList
import cats.data.Validated._
import cats.instances.future._
import cats.syntax.apply._
import cats.syntax.either._
import de.bwhc.util.Logging
import de.bwhc.mtb.data.entry.api.{
  DataQualityReport,
  MTBDataService,
  MTBDataServiceProvider,
  PatientDataInfo
}
import DataQualityReport.Issue.Severity
import de.bwhc.mtb.dtos._
import de.bwhc.mtb.views.{
  MTBFileView,
  NotAvailable
}
import de.bwhc.mtb.views.mappings._
import de.bwhc.mtb.query.api.{
  DataOps,
  QueryService
}




class MTBDataServiceProviderImpl extends MTBDataServiceProvider
{
    
  def getInstance: MTBDataService = {
    MTBDataServiceImpl.instance
  }
    
}

object MTBDataServiceImpl
{

  private val localSite =
    Option(System.getProperty("bwhc.zpm.site"))
      .map(ZPM(_))
      .get  //TODO: improve configurability

  private val db =
    MTBDataDB.getInstance.get

  private val queryService =
   QueryService.getInstance.get


  private val validator: DataValidator = new DefaultDataValidator


  val instance =
    new MTBDataServiceImpl(
      localSite,
      validator,
      db,
      queryService
    )

}


object Matchers
{

  import Severity._

  object FatalErrors
  {
    def unapply(rep: DataQualityReport): Boolean = {
      rep.issues.map(_.severity).toList contains Fatal
    }
  }

  object Errors
  {
    def unapply(rep: DataQualityReport): Boolean = {
      rep.issues.map(_.severity).toList contains Error
    }
  }

  object AtMostWarnings
  {
    def unapply(rep: DataQualityReport): Boolean = {
      rep.issues.map(_.severity).toList forall (s => s == Warning || s == Info)
    }
  }

  object OnlyInfos
  {
    def unapply(rep: DataQualityReport): Boolean = {
      rep.issues.map(_.severity).toList forall (_ == Info)
    }
  }

}


class MTBDataServiceImpl
(
  private val localSite: ZPM,
  private val validator: DataValidator,
  private val db: MTBDataDB,
  private val queryService: QueryService
)
extends MTBDataService
with Logging
{

  import Matchers._

  import MTBDataService.Command._
  import MTBDataService.Response._
  import MTBDataService.Error._

  import ValueSets._


  implicit val patientDataInfo: ((Patient,DataQualityReport)) => PatientDataInfo = {
    case (patient,dataQC) =>

      val issueMap =
        dataQC.issues
          .toList
          .groupBy(_.severity)

      PatientDataInfo(
        patient.id,
        ValueSet[Gender.Value].displayOf(patient.gender).get,
        patient.birthDate.toRight(NotAvailable),
        issueMap.getOrElse(Severity.Error,  List.empty).size,
        issueMap.getOrElse(Severity.Warning,List.empty).size,
        issueMap.getOrElse(Severity.Info,   List.empty).size
      )
  }


  override def process(
    cmd: MTBDataService.Command
  )(
    implicit ec: ExecutionContext
  ): Future[Either[MTBDataService.Error,MTBDataService.Response]] = {

    cmd match {

      //-----------------------------------------------------------------------
      case Upload(mtbfile) => {

        log.info(s"Handling MTBFile upload for Patient ${mtbfile.patient.id.value}")

        val result =
          for {
            validation <- validator check mtbfile

            response <-
              validation match {

                case Invalid(qcReport) => {

                  qcReport match {

                    case FatalErrors() => {
                      log.error(s"FATAL issues detected, refusing data upload")
                      Future.successful(InvalidData(qcReport).asLeft[MTBDataService.Response])
                    }

                    case Errors() => {
                      log.warn(s"'ERROR'-level issues detected, storing DataQualityReport")
                      (
                        db save postprocess(mtbfile),
                        db save qcReport
                      )
                      .mapN(
                        (_,qc) => IssuesDetected(qc).asRight[MTBDataService.Error]
                      )
                    }

                    case AtMostWarnings() => {
                      log.info(s"At most 'WARNING'-level issues detected. Forwarding data to QueryService, but storing DataQualityReport")

                      val mtbfilePr = postprocess(mtbfile)

                      (queryService ! DataOps.Command.Upload(mtbfilePr))
                        .andThen {
                          case Success(_) => {
                            db save mtbfilePr
                            db save qcReport
                          }
                        }
                        .map(_ => IssuesDetected(qcReport).asRight[MTBDataService.Error])

                    }

                    case _ => {
                      log.info(s"No issues detected, forwarding data to QueryService")
                      processAcceptable(mtbfile)
                    }

                  }
                
                }

                case Valid(_) => {
                  log.info(s"No issues detected, forwarding data to QueryService")
                  processAcceptable(mtbfile)
                }
  
              }
  
          } yield response

        result.recover {
          case t => UnspecificError(t.getMessage).asLeft[MTBDataService.Response]
        }

      }

      //-----------------------------------------------------------------------
      case Delete(patId) => {

        log.info(s"Handling Delete request for data of $patId")

        (
          db.deleteAll(patId),
          queryService ! DataOps.Command.Delete(patId)
        )
        .mapN(
          (_,_) => Deleted(patId).asRight[MTBDataService.Error]
        )
        .recover {
          case t => UnspecificError(t.getMessage).asLeft[MTBDataService.Response]
        }
      }
    }

  }


  private def processAcceptable(
    mtbfile: MTBFile
  )(
    implicit ec: ExecutionContext
  ): Future[Either[MTBDataService.Error,MTBDataService.Response]] = {

    (queryService ! DataOps.Command.Upload(postprocess(mtbfile)))
      .andThen {
        case Success(_) => db deleteAll mtbfile.patient.id
      }
      .map(_ => Imported(mtbfile).asRight[MTBDataService.Error])

  }


  import scala.util.chaining._

  private def postprocess(mtbfile: MTBFile): MTBFile = {
    mtbfile
      .pipe(assignSite)
      .pipe(harmonizeNGSReports)
  }


  private def assignSite(mtbfile: MTBFile): MTBFile =
   mtbfile.copy(
     patient = mtbfile.patient.copy(managingZPM = Some(localSite))
   )


  private def harmonizeNGSReports(mtbfile: MTBFile): MTBFile = {

    mtbfile.ngsReports match {
      case Some(reports) if (!reports.isEmpty) =>
        mtbfile.copy(ngsReports = Some(reports.map(harmonize)))

      case _ => mtbfile
    }

  }


  private def harmonize(ngsReport: SomaticNGSReport): SomaticNGSReport = {

    log.debug(s"Harmonizing gene IDs/symbols in NGSReport ${ngsReport.id.value}")

    val harmonizedSnvs =
      ngsReport.simpleVariants.map(harmonizeSNVs)

    val harmonizedCnvs =
      ngsReport.copyNumberVariants.map(harmonizeCNVs)

    ngsReport.copy(
      simpleVariants = harmonizedSnvs,
      copyNumberVariants = harmonizedCnvs
    )

  }


  private def harmonizeSNVs(snvs: List[SimpleVariant]): List[SimpleVariant] = {

    snvs.map(
      snv =>
        snv.gene match {

          case Some(coding) =>
            HGNCOps.complete(coding)
              .fold(
                snv tap (_ => log warn s"Failure resolving HGNC Coding on SNV ${snv.id.value}")
              )(
                c => snv.copy(gene = Some(c))
              )

          case None => snv
        }
    )
  }

  private def harmonizeCNVs(cnvs: List[CNV]): List[CNV] = {

    import cats.syntax.traverse._
    import cats.instances.option._
    import cats.instances.list._

    // Harmonize "reportedAffectedGenes"...
    cnvs.map(
      cnv =>
        cnv.reportedAffectedGenes match {
         case Some(codings) => 
           codings.traverse(HGNCOps.complete)
             .fold(
               cnv tap (_ => log.warn(s"Failure resolving HGNC Codings on CNV ${cnv.id.value}"))
             )(
               cs => cnv.copy(
                 reportedAffectedGenes = Some(cs)
               )      
             )
         case None => cnv
      }
    )
    // ... then "copyNumberNeutralLoH"
    .map(
      cnv =>
        cnv.copyNumberNeutralLoH match {
         case Some(codings) => 
           codings.traverse(HGNCOps.complete)
             .fold(
               cnv tap (_ => log.warn(s"Failure resolving HGNC Codings on CNV ${cnv.id.value}"))
             )(
               cs => cnv.copy(
                 copyNumberNeutralLoH = Some(cs)
               )      
             )
         case None => cnv
      }
    )
  }



  override def patientsWithIncompleteData(
    filterCriteria: MTBDataService.Filter
  )(
    implicit ec: ExecutionContext
  ): Future[Iterable[PatientDataInfo]] = {
  
    def toPredicate(filter: MTBDataService.Filter): ((Patient,DataQualityReport)) => Boolean = {
 
      case (patient,dataQC) => 
 
        val MTBDataService.Filter(genders,errorMsg,entityType,attribute) = filter
 
        genders.fold(true)(gs => gs.contains(patient.gender)) &&
        errorMsg.fold(true)(msg => dataQC.issues.find(_.message contains (msg)).isDefined) &&
        entityType.fold(true)(entity => dataQC.issues.map(_.location.entryType).find(_ contains (entity)).isDefined) &&
        attribute.fold(true)(attr => dataQC.issues.map(_.location.attribute).find(_ contains (attr)).isDefined)
 
    }


    log.info(s"Handling request for Patients with data quality issues")

    for {
      pairs  <- patientDataQCReportPairs
      result =
        pairs
          .filter(toPredicate(filterCriteria))
          .map(_.mapTo[PatientDataInfo])
    } yield result  

  }


  private def patientDataQCReportPairs(
    implicit ec: ExecutionContext
  ): Future[Iterable[(Patient,DataQualityReport)]] = {
  
    log.info(s"Handling request for Patients with data quality issues")

    for {
      patients <- db.patients
      dataQC   <- db.dataQcReports
      pairs    =  patients.map(pat => (pat,dataQC.find(_.patient == pat.id).get) )
    } yield pairs
    
  }



  override def mtbfile(
    patient: Patient.Id
  )(
    implicit ec: ExecutionContext
  ): Future[Option[MTBFile]] = {

    log.info(s"Handling request for MTBFile of Patient ${patient.value}")

    db.mtbfile(patient)

  }


  override def mtbfileView(
    patient: Patient.Id
  )(
    implicit ec: ExecutionContext
  ): Future[Option[MTBFileView]] = {

    for {
      mtbf <- mtbfile(patient)
      view =  mtbf.map(_.mapTo[MTBFileView])
    } yield view

  }


  override def dataQualityReport(
    patient: Patient.Id
  )(
    implicit ec: ExecutionContext
  ): Future[Option[DataQualityReport]] = {

    log.info(s"Handling request for DataQualityReport of Patient ${patient.value}")

    db.dataQcReportOf(patient)

  }



}

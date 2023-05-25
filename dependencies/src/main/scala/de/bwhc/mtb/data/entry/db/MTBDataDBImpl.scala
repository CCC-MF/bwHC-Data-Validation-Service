package de.bwhc.mtb.data.entry.db


import java.io.File
import scala.concurrent.{
  ExecutionContext,
  Future
}
import de.ekut.tbi.repo.AsyncRepository
import de.ekut.tbi.repo.fs.AsyncFSBackedRepository
import de.ekut.tbi.repo.fs.AsyncFSBackedInMemRepository
import de.bwhc.mtb.dtos._
import de.bwhc.mtb.data.entry.api.DataQualityReport
import de.bwhc.mtb.data.entry.impl.{
  MTBDataDB,
  MTBDataDBProvider
}


class MTBDataDBProviderImpl extends MTBDataDBProvider
{

  def getInstance: MTBDataDB = {
    MTBDataDBImpl.instance
  }

}



object MTBDataDBImpl
{

  private val dataDir =
    Option(System.getProperty("bwhc.data.entry.dir"))
      .map(new File(_))
      .get

  private val mtbfileDB: AsyncRepository[MTBFile,Patient.Id] =
    AsyncFSBackedRepository(
      new File(dataDir,"mtbfiles/"),
      "MTBFile",
      _.patient.id,
      _.value
    )

  private val patientDB: AsyncRepository[Patient,Patient.Id] =
    AsyncFSBackedInMemRepository(
      new File(dataDir,"patients/"),
      "Patient",
      _.id,
      _.value
    )

  private val dataReportDB: AsyncRepository[DataQualityReport,Patient.Id] =
    AsyncFSBackedInMemRepository(
      new File(dataDir,"dataQualityReports/"),
      "DataQualityReport",
      _.patient,
      _.value
    )


  val instance =
    new MTBDataDBImpl(
      mtbfileDB,
      patientDB,
      dataReportDB
    )

}



class MTBDataDBImpl
(
  val mtbfileDB: AsyncRepository[MTBFile,Patient.Id],
  val patientDB: AsyncRepository[Patient,Patient.Id],
  val dataReportDB: AsyncRepository[DataQualityReport,Patient.Id],
)
extends MTBDataDB
{

  import cats.instances.future._
  import cats.syntax.apply._


  def save(
    mtbfile: MTBFile
  )(
    implicit ec: ExecutionContext
  ): Future[MTBFile] = {
    (
      mtbfileDB.save(mtbfile),
      patientDB.save(mtbfile.patient)
    )
    .mapN((_,_) => mtbfile)
  }


  def patients(
    implicit ec: ExecutionContext
  ): Future[Iterable[Patient]] = {
    patientDB.query(_ => true)
  }


  def mtbfile(
    id: Patient.Id,
  )(
    implicit ec: ExecutionContext
  ): Future[Option[MTBFile]] = {
    mtbfileDB.get(id)
  }


  def save(
    report: DataQualityReport
  )(
    implicit ec: ExecutionContext
  ): Future[DataQualityReport] = {
    dataReportDB.save(report)
  }


  def dataQcReports(
    implicit ec: ExecutionContext
  ): Future[Iterable[DataQualityReport]] = {
    dataReportDB.query(_ => true)
  }

  def dataQcReportOf(
    patId: Patient.Id
  )(
    implicit ec: ExecutionContext
  ): Future[Option[DataQualityReport]] = {
    dataReportDB.get(patId)
  }


  def deleteAll(
    id: Patient.Id,
  )(
    implicit ec: ExecutionContext
  ): Future[Option[MTBFile]] = {

    (
      mtbfileDB.delete(id),
      patientDB.delete(id),
      dataReportDB.delete(id)
    )
    .mapN((deleted,_,_) => deleted)
  }


}

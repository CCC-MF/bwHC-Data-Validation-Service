package de.bwhc.mtb.data.entry.dtos


import java.time.LocalDate
import play.api.libs.json.Json



case class NoTargetFinding
(
  patient: Patient.Id,
  diagnosis: Diagnosis.Id,
  issuedOn: Option[LocalDate]
)

object NoTargetFinding
{
  implicit val format = Json.format[NoTargetFinding]
}


case class CarePlan
(
  id: CarePlan.Id,
  patient: Patient.Id,
  diagnosis: Diagnosis.Id,
  issuedOn: Option[LocalDate],
  description: Option[String],
  noTargetFinding: Option[NoTargetFinding],
  recommendations: Option[List[TherapyRecommendation.Id]],
  geneticCounsellingRequest: Option[GeneticCounsellingRequest.Id],
  rebiopsyRequests: Option[List[RebiopsyRequest.Id]],
  studyInclusionRequests: Option[List[StudyInclusionRequest.Id]]
)


object CarePlan
{

  case class Id(value: String) extends AnyVal

  implicit val formatId = Json.valueFormat[Id]

  implicit val format = Json.format[CarePlan]

}

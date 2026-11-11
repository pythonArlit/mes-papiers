package app.mespapiers

import app.mespapiers.core.model.Category
import app.mespapiers.core.model.Field
import app.mespapiers.core.model.FieldType
import app.mespapiers.core.model.Ids
import app.mespapiers.core.model.Record
import java.time.LocalDate

/**
 * Jeu de données fictives, manifestement fausses, propre à la variante demo.
 * tools/check-apk.sh vérifie que la chaîne DEMO_MARKER est absente de l'APK prod.
 */
object DemoData {
    const val DEMO_MARKER = "MESPAPIERS-DEMO-FICTIF"

    fun records(today: LocalDate): List<Record> = listOf(
        Record(
            Ids.new(), Category.IDENTITY, "Passeport (EXEMPLE)", tags = listOf("exemple", "voyage"), favorite = true,
            fields = listOf(
                Field("holder", FieldType.TEXT, "Camille EXEMPLE"),
                Field("number", FieldType.IDENTIFIER, "00FICTIF000", sensitive = true),
                Field("issuer", FieldType.TEXT, "République de Démonstration"),
            ),
            expiry = today.plusDays(21),
        ),
        Record(
            Ids.new(), Category.DIPLOMA, "Diplôme de démonstration", tags = listOf("exemple"),
            fields = listOf(
                Field("institution", FieldType.TEXT, "Université Fictive de Nulle-Part"),
                Field("reference", FieldType.IDENTIFIER, "DEMO-0000-0001"),
            ),
        ),
        Record(
            Ids.new(), Category.BANK, "RIB d'exemple", tags = listOf("exemple"),
            fields = listOf(
                Field("bank", FieldType.TEXT, "Banque Imaginaire"),
                Field("account_number", FieldType.IDENTIFIER, "000000000000", sensitive = true),
            ),
        ),
        Record(Ids.new(), Category.OTHER, "Assurance expirée (EXEMPLE)", expiry = today.minusDays(40)),
    )
}

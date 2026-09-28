package dev.lcv.maestro.sessao

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * As migrações automáticas sobre o esquema exportado (`schemas/`): o banco é
 * criado na versão antiga, recebe linhas, migra e é validado contra o esquema
 * novo pelo `MigrationTestHelper` oficial do Room.
 */
@RunWith(AndroidJUnit4::class)
class MigracaoTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BancoDaSessao::class.java)

    @Test
    fun deV2ParaV3AcrescentaOEmailNuloEPreservaAsConfiguracoes() {
        val nome = "migracao-${UUID.randomUUID()}.db"
        helper.createDatabase(nome, 2).apply {
            execSQL(
                "INSERT INTO configuracoes (id, protocolo, tetoDeCustoE8, tetoDeMinutos, maxCiclos, taxasJson, atualizadaEm) " +
                    "VALUES ('default', 'protocolo', 100, NULL, 2, '{}', '2026-09-28T00:00:00Z')",
            )
            close()
        }
        val migrado = helper.runMigrationsAndValidate(nome, 3, true)
        migrado.query("SELECT emailDeContato, protocolo, tetoDeCustoE8 FROM configuracoes").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertEquals("protocolo", cursor.getString(1))
            assertEquals(100L, cursor.getLong(2))
        }
        migrado.close()
    }
}

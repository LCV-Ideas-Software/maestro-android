package dev.lcv.maestro.protocolo

import java.time.Instant

/**
 * O que um módulo que grava registros de evidência precisa produzir no mesmo
 * formato que este: o hash hexadecimal e o carimbo de tempo do canônico
 * (`evidence_id`/`sha256_bytes` e `Utc::now().to_rfc3339()`). Os carimbos são
 * ordenados como texto na listagem de links, então os dois módulos têm de
 * escrevê-los igual.
 */
public object FormatoDoRegistro {
    /** SHA-256 em hexadecimal minúsculo. */
    public fun sha256(valor: ByteArray): String = TextoRust.sha256(valor)

    /** SHA-256 do UTF-8 de [valor], em hexadecimal minúsculo. */
    public fun sha256(valor: String): String = TextoRust.sha256(valor)

    /** `to_rfc3339()` do `chrono`: fração mínima e deslocamento `+00:00`. */
    public fun rfc3339(instante: Instant): String = TextoRust.rfc3339(instante)
}

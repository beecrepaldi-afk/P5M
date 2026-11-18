// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import androidx.core.content.FileProvider

/**
 * FileProvider proprio do P5M (diario e APK de atualizacao).
 *
 * Com a MESMA classe do provider do chiaki-ng, o Quest entregava os pedidos de
 * io.github.gblandro.p5m.diagnostics a instancia do outro, e quem abria o
 * arquivo recebia "authority does not match" -- o instalador mostrava
 * "problema ao analisar o pacote". Uma subclasse vazia basta para separar.
 */
class ProvedorDeArquivos : FileProvider()

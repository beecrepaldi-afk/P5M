package io.github.gblandro.p5m
import android.content.Context
// A calibragem nao e o objeto desta regressao; conserva os eixos recebidos.
class StickPrefs(context: Context) {
    val left = 0; val right = 0
    companion object {
        var offset: Short = 0
        fun aplicar(x: Short, y: Short, calibration: Int) = (x - offset).toShort() to y
    }
}

package com.exemple.llistacompra

import android.graphics.Color
import android.view.View
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Hace que la cabecera verde llegue hasta arriba del todo (detrás de la barra de estado)
 * y evita que el contenido quede tapado por la barra de estado, la de navegación o el teclado.
 *
 * Llamar justo después de setContentView().
 */
fun AppCompatActivity.configurarBarrasSistema(cabecera: View) {
    // Barra de estado transparente con iconos blancos (fondo verde)
    enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))

    val paddingTopOriginal = cabecera.paddingTop
    val raiz = findViewById<View>(android.R.id.content)

    ViewCompat.setOnApplyWindowInsetsListener(raiz) { vista, insets ->
        val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val teclado = insets.getInsets(WindowInsetsCompat.Type.ime())

        // Arriba: la cabecera crece lo que mide la barra de estado
        cabecera.updatePadding(top = paddingTopOriginal + barras.top)
        // Abajo: dejamos sitio a la barra de navegación o al teclado
        vista.updatePadding(bottom = maxOf(barras.bottom, teclado.bottom))

        insets
    }
}

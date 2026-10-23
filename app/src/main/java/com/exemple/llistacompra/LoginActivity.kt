package com.exemple.llistacompra

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import kotlin.jvm.java

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var accesoGoogle: AccesoGoogle

    private lateinit var btnLogin: Button
    private lateinit var btnGoogle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = FirebaseAuth.getInstance()

        // Si ya hay una sesión iniciada, vamos directos a la lista
        if (auth.currentUser != null) {
            irAMain()
            return
        }

        setContentView(R.layout.login)
        configurarBarrasSistema(findViewById(R.id.cabecera))

        val inputEmail = findViewById<TextInputEditText>(R.id.inputEmail)
        val inputPassword = findViewById<TextInputEditText>(R.id.inputPassword)
        val btnGoToRegister = findViewById<Button>(R.id.btnGoToRegister)
        btnLogin = findViewById(R.id.btnLogin)
        btnGoogle = findViewById(R.id.btnGoogle)

        accesoGoogle = AccesoGoogle(this, onCargando = ::cargando, onExito = ::irAMain)

        // ── Correo y contraseña ─────────────────────────────
        btnLogin.setOnClickListener {
            val email = inputEmail.text.toString().trim()
            // La contraseña NO se recorta: los espacios pueden formar parte de ella
            val password = inputPassword.text.toString()

            if (email.isEmpty() || password.isEmpty()) {
                toast("Por favor, completa todos los campos")
                return@setOnClickListener
            }

            cargando(true)

            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    cargando(false)
                    if (task.isSuccessful) {
                        irAMain()
                    } else {
                        toast(mensajeDeError(task.exception), largo = true)
                    }
                }
        }

        // Enter en la contraseña = pulsar "Entrar"
        inputPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                btnLogin.performClick()
                true
            } else false
        }

        // ── Google ──────────────────────────────────────────
        btnGoogle.setOnClickListener { accesoGoogle.iniciar() }

        btnGoToRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    // ── Helpers ─────────────────────────────────────────────

    /** Desactiva los botones mientras se está iniciando sesión. */
    private fun cargando(activo: Boolean) {
        btnLogin.isEnabled = !activo
        btnGoogle.isEnabled = !activo
    }

    private fun mensajeDeError(e: Exception?): String = when (e) {
        is FirebaseAuthInvalidUserException -> "No existe ninguna cuenta con ese correo"
        is FirebaseAuthInvalidCredentialsException -> "Correo o contraseña incorrectos"
        is FirebaseNetworkException -> "Sin conexión. Revisa tu internet"
        else -> "No se ha podido iniciar sesión"
    }

    private fun toast(mensaje: String, largo: Boolean = false) =
        Toast.makeText(this, mensaje, if (largo) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    private fun irAMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

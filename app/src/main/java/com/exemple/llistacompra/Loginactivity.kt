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

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

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
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val btnGoToRegister = findViewById<Button>(R.id.btnGoToRegister)

        btnLogin.setOnClickListener {
            val email = inputEmail.text.toString().trim()
            // La contraseña NO se recorta: los espacios pueden formar parte de ella
            val password = inputPassword.text.toString()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Por favor, completa todos los campos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnLogin.isEnabled = false

            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    btnLogin.isEnabled = true
                    if (task.isSuccessful) {
                        irAMain()
                    } else {
                        Toast.makeText(this, mensajeDeError(task.exception), Toast.LENGTH_LONG).show()
                    }
                }
        }

        // Enter en la contraseña = pulsar "Iniciar sesión"
        inputPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                btnLogin.performClick()
                true
            } else false
        }

        btnGoToRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun mensajeDeError(e: Exception?): String = when (e) {
        is FirebaseAuthInvalidUserException -> "No existe ninguna cuenta con ese correo"
        is FirebaseAuthInvalidCredentialsException -> "Correo o contraseña incorrectos"
        is FirebaseNetworkException -> "Sin conexión. Revisa tu internet"
        else -> "No se ha podido iniciar sesión"
    }

    private fun irAMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
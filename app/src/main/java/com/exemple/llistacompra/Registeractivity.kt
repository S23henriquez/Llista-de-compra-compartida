package com.exemple.llistacompra

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat.startActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException

class RegisterActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.register)

        auth = FirebaseAuth.getInstance()

        val inputEmail = findViewById<TextInputEditText>(R.id.inputRegisterEmail)
        val inputPassword = findViewById<TextInputEditText>(R.id.inputRegisterPassword)
        val btnRegister = findViewById<Button>(R.id.btnRegister)
        val btnGoToLogin = findViewById<Button>(R.id.btnGoToLogin)

        btnRegister.setOnClickListener {
            val email = inputEmail.text.toString().trim()
            val password = inputPassword.text.toString()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Completa todos los campos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                inputEmail.error = "Correo no válido"
                return@setOnClickListener
            }

            if (password.length < 6) {
                inputPassword.error = "Mínimo 6 caracteres"
                return@setOnClickListener
            }

            btnRegister.isEnabled = false

            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    btnRegister.isEnabled = true
                    if (task.isSuccessful) {
                        // Limpiamos la pila para que "atrás" no vuelva al login/registro
                        val intent = Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        startActivity(intent)
                        finish()
                    } else {
                        Toast.makeText(this, mensajeDeError(task.exception), Toast.LENGTH_LONG).show()
                    }
                }
        }

        // El Login ya está debajo en la pila: basta con cerrar esta pantalla
        btnGoToLogin.setOnClickListener { finish() }

        inputPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                btnRegister.performClick()
                true
            } else false
        }
    }

    private fun mensajeDeError(e: Exception?): String = when (e) {
        is FirebaseAuthUserCollisionException -> "Ya existe una cuenta con ese correo"
        is FirebaseAuthWeakPasswordException -> "La contraseña es demasiado débil"
        is FirebaseAuthInvalidCredentialsException -> "Correo no válido"
        is FirebaseNetworkException -> "Sin conexión. Revisa tu internet"
        else -> "No se ha podido crear la cuenta"
    }
}
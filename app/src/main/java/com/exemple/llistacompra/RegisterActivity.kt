package com.exemple.llistacompra

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException

class RegisterActivity : AppCompatActivity() {

    companion object {
        const val LONGITUD_MINIMA = 8
    }

    private lateinit var auth: FirebaseAuth

    // Cada requisito: el TextView que lo muestra + la regla que comprueba
    private lateinit var requisitos: List<Pair<TextView, (String) -> Boolean>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.register)
        configurarBarrasSistema(findViewById(R.id.cabecera))

        auth = FirebaseAuth.getInstance()

        val tilEmail = findViewById<TextInputLayout>(R.id.tilRegisterEmail)
        val tilPassword = findViewById<TextInputLayout>(R.id.tilRegisterPassword)
        val tilConfirm = findViewById<TextInputLayout>(R.id.tilRegisterPasswordConfirm)

        val inputEmail = findViewById<TextInputEditText>(R.id.inputRegisterEmail)
        val inputPassword = findViewById<TextInputEditText>(R.id.inputRegisterPassword)
        val inputConfirm = findViewById<TextInputEditText>(R.id.inputRegisterPasswordConfirm)

        val btnRegister = findViewById<Button>(R.id.btnRegister)
        val btnGoToLogin = findViewById<Button>(R.id.btnGoToLogin)
        val btnGoogle = findViewById<Button>(R.id.btnGoogle)

        // ── Registrarse con Google (crea la cuenta si no existe) ──
        val accesoGoogle = AccesoGoogle(
            this,
            onCargando = { activo ->
                btnRegister.isEnabled = !activo
                btnGoogle.isEnabled = !activo
            },
            onExito = ::irAMain
        )
        btnGoogle.setOnClickListener { accesoGoogle.iniciar() }

        requisitos = listOf(
            findViewById<TextView>(R.id.reqLongitud) to { p: String -> p.length >= LONGITUD_MINIMA },
            findViewById<TextView>(R.id.reqMayuscula) to { p: String -> p.any { it.isUpperCase() } },
            findViewById<TextView>(R.id.reqMinuscula) to { p: String -> p.any { it.isLowerCase() } },
            findViewById<TextView>(R.id.reqNumero) to { p: String -> p.any { it.isDigit() } },
            findViewById<TextView>(R.id.reqEspecial) to { p: String -> p.any { !it.isLetterOrDigit() && !it.isWhitespace() } }
        )

        // ── Validación en vivo ──────────────────────────────

        inputEmail.doAfterTextChanged { tilEmail.error = null }

        inputPassword.doAfterTextChanged { texto ->
            tilPassword.error = null
            actualizarRequisitos(texto?.toString().orEmpty())
            // Si ya había escrito la confirmación, la volvemos a comprobar
            if (!inputConfirm.text.isNullOrEmpty()) {
                comprobarCoincidencia(tilConfirm, texto.toString(), inputConfirm.text.toString())
            }
        }

        inputConfirm.doAfterTextChanged { texto ->
            comprobarCoincidencia(tilConfirm, inputPassword.text.toString(), texto.toString())
        }

        // ── Registro ────────────────────────────────────────

        btnRegister.setOnClickListener {
            val email = inputEmail.text.toString().trim()
            val password = inputPassword.text.toString()
            val confirm = inputConfirm.text.toString()

            var valido = true

            if (email.isEmpty()) {
                tilEmail.error = "Introduce tu correo"
                valido = false
            } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                tilEmail.error = "Correo no válido"
                valido = false
            }

            if (password.isEmpty()) {
                tilPassword.error = "Introduce una contraseña"
                valido = false
            } else if (!esPasswordSegura(password)) {
                tilPassword.error = "La contraseña no cumple todos los requisitos"
                valido = false
            }

            if (confirm.isEmpty()) {
                tilConfirm.error = "Repite la contraseña"
                valido = false
            } else if (confirm != password) {
                tilConfirm.error = "Las contraseñas no coinciden"
                valido = false
            }

            if (!valido) return@setOnClickListener

            btnRegister.isEnabled = false

            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    btnRegister.isEnabled = true
                    if (task.isSuccessful) {
                        irAMain()
                    } else {
                        Toast.makeText(this, mensajeDeError(task.exception), Toast.LENGTH_LONG).show()
                    }
                }
        }

        // El Login ya está debajo en la pila: basta con cerrar esta pantalla
        btnGoToLogin.setOnClickListener { finish() }

        // "Hecho" en el teclado del último campo = pulsar "Crear cuenta"
        inputConfirm.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                btnRegister.performClick()
                true
            } else false
        }
    }

    // ── Helpers de contraseña ───────────────────────────────

    private fun esPasswordSegura(password: String) =
        requisitos.all { (_, regla) -> regla(password) }

    /** Pinta cada requisito en verde con check si se cumple, o gris si no. */
    private fun actualizarRequisitos(password: String) {
        val verde = ContextCompat.getColor(this, R.color.primary)
        val gris = ContextCompat.getColor(this, R.color.text_secondary)

        requisitos.forEach { (vista, regla) ->
            val cumple = regla(password)
            vista.setTextColor(if (cumple) verde else gris)
            vista.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (cumple) R.drawable.ic_req_ok else R.drawable.ic_req_pendiente, 0, 0, 0
            )
        }
    }

    private fun comprobarCoincidencia(til: TextInputLayout, password: String, confirm: String) {
        til.error = if (confirm.isNotEmpty() && confirm != password) "Las contraseñas no coinciden" else null
    }

    /** Va a la lista y limpia la pila para que "atrás" no vuelva al login/registro. */
    private fun irAMain() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

    private fun mensajeDeError(e: Exception?): String = when (e) {
        is FirebaseAuthUserCollisionException -> "Ya existe una cuenta con ese correo"
        is FirebaseAuthWeakPasswordException -> "La contraseña es demasiado débil"
        is FirebaseAuthInvalidCredentialsException -> "Correo no válido"
        is FirebaseNetworkException -> "Sin conexión. Revisa tu internet"
        else -> "No se ha podido crear la cuenta"
    }
}
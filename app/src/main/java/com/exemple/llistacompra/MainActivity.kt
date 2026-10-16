package com.exemple.llistacompra

import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

// ── Modelos ─────────────────────────────────────────────

data class Producto(
    val id: String = "",
    val nombre: String = "",
    val completado: Boolean = false
)

data class Categoria(
    val id: String = "",
    val nombre: String = "",
    val productos: Map<String, Producto> = emptyMap()
)

// ── Activity ────────────────────────────────────────────

class MainActivity : AppCompatActivity() {

    private lateinit var listaProductos: LinearLayout
    private lateinit var spinnerCategoria: Spinner

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val dbRef by lazy { FirebaseDatabase.getInstance().getReference("listas") }
    private val uid get() = auth.currentUser?.uid ?: ""
    private val categoriasRef get() = dbRef.child(uid).child("categorias")

    private val categorias = mutableMapOf<String, Categoria>()

    // Estado del spinner: ids en el mismo orden que los nombres mostrados
    private var idsSpinner: List<String> = emptyList()
    private var categoriaSeleccionadaId: String? = null

    // Guardamos el listener para quitarlo al cerrar la Activity
    private var refEscuchada: DatabaseReference? = null
    private var listenerCategorias: ValueEventListener? = null

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Sin sesión no tiene sentido estar aquí
        if (auth.currentUser == null) {
            irAlLogin()
            return
        }

        setContentView(R.layout.activity_main)
        configurarBarrasSistema(findViewById(R.id.cabecera))

        val inputProducte  = findViewById<TextInputEditText>(R.id.inputProducte)
        val btnAfegir      = findViewById<FloatingActionButton>(R.id.btnAfegir)
        val btnLimpieza    = findViewById<Button>(R.id.btnLimpieza)
        val btnVolverLogin = findViewById<Button>(R.id.btnVolverLogin)
        val txtUsuario     = findViewById<TextView>(R.id.txtUsuario)
        val btnNuevaCat    = findViewById<Button>(R.id.btnNuevaCategoria)

        spinnerCategoria = findViewById(R.id.spinnerCategoria)
        listaProductos   = findViewById(R.id.listaProductos)

        // Recordamos qué categoría ha elegido el usuario
        spinnerCategoria.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                categoriaSeleccionadaId = idsSpinner.getOrNull(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        mostrarInfoUsuario(txtUsuario)
        escucharCategorias()

        // Enter del teclado
        inputProducte.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                btnAfegir.performClick()
                true
            } else false
        }

        // ➕ Añadir producto
        btnAfegir.setOnClickListener {
            val nombre = inputProducte.text?.toString()?.trim().orEmpty()

            if (nombre.isBlank()) {
                inputProducte.error = "Introduce un producto"
                return@setOnClickListener
            }

            val catId = categoriaSeleccionadaId
            if (catId == null || catId !in categorias) {
                toast("Crea primero una categoría")
                return@setOnClickListener
            }

            agregarProductoEnFirebase(catId, nombre)

            inputProducte.text?.clear()
            ocultarTeclado(inputProducte)
        }

        btnNuevaCat.setOnClickListener {
            mostrarDialogoTexto("Nueva categoría", "Nombre de la categoría", "", "Crear") { nombre ->
                if (existeCategoria(nombre)) toast("Ya existe esa categoría")
                else agregarCategoriaEnFirebase(nombre)
            }
        }

        btnLimpieza.setOnClickListener {
            val hayCompletados = categorias.values.any { cat -> cat.productos.values.any { it.completado } }
            if (!hayCompletados) {
                toast("No hay productos completados")
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Limpiar completados")
                .setMessage("¿Eliminar todos los productos completados?")
                .setPositiveButton("Eliminar") { _, _ -> limpiarCompletados() }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        btnVolverLogin.setOnClickListener {
            auth.signOut()
            irAlLogin()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        listenerCategorias?.let { refEscuchada?.removeEventListener(it) }
    }

    // ── Firebase ─────────────────────────────────────────

    private fun escucharCategorias() {
        if (uid.isEmpty()) return

        val ref = categoriasRef
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                categorias.clear()

                snapshot.children.forEach { catSnap ->
                    val catId  = catSnap.key ?: return@forEach
                    val nombre = catSnap.child("nombre").getValue(String::class.java) ?: return@forEach

                    val productos = mutableMapOf<String, Producto>()

                    catSnap.child("productos").children.forEach { prodSnap ->
                        val prodId = prodSnap.key ?: return@forEach
                        val prodNombre = prodSnap.child("nombre").getValue(String::class.java) ?: return@forEach
                        val completado = prodSnap.child("completado").getValue(Boolean::class.java) ?: false

                        productos[prodId] = Producto(prodId, prodNombre, completado)
                    }

                    categorias[catId] = Categoria(catId, nombre, productos)
                }

                reconstruirUI()
                actualizarSpinner()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Firebase", error.message)
                toast("Error al cargar los datos")
            }
        }

        ref.addValueEventListener(listener)
        refEscuchada = ref
        listenerCategorias = listener
    }

    // ── CRUD ─────────────────────────────────────────────

    private fun agregarCategoriaEnFirebase(nombre: String) {
        if (uid.isEmpty()) return
        val catId = categoriasRef.push().key ?: return
        // La nueva categoría queda seleccionada en el spinner
        categoriaSeleccionadaId = catId
        categoriasRef.child(catId).child("nombre").setValue(nombre)
    }

    private fun editarCategoriaEnFirebase(catId: String, nuevoNombre: String) {
        if (uid.isEmpty()) return
        categoriasRef.child(catId).child("nombre").setValue(nuevoNombre)
    }

    private fun eliminarCategoriaEnFirebase(catId: String) {
        if (uid.isEmpty()) return
        categoriasRef.child(catId).removeValue()
    }

    private fun agregarProductoEnFirebase(catId: String, nombre: String) {
        if (uid.isEmpty()) return
        val productosRef = categoriasRef.child(catId).child("productos")
        val prodId = productosRef.push().key ?: return

        val producto = mapOf(
            "nombre" to nombre,
            "completado" to false
        )

        productosRef.child(prodId).setValue(producto)
    }

    private fun toggleProductoCompletado(catId: String, prodId: String, completado: Boolean) {
        if (uid.isEmpty()) return
        categoriasRef.child(catId).child("productos").child(prodId)
            .child("completado").setValue(completado)
    }

    private fun editarProductoEnFirebase(catId: String, prodId: String, nuevoNombre: String) {
        if (uid.isEmpty()) return
        categoriasRef.child(catId).child("productos").child(prodId)
            .child("nombre").setValue(nuevoNombre)
    }

    private fun eliminarProductoEnFirebase(catId: String, prodId: String) {
        if (uid.isEmpty()) return
        categoriasRef.child(catId).child("productos").child(prodId).removeValue()
    }

    // Borra todos los completados en UNA sola escritura
    private fun limpiarCompletados() {
        if (uid.isEmpty()) return

        val borrados = mutableMapOf<String, Any?>()
        categorias.forEach { (catId, cat) ->
            cat.productos.values.filter { it.completado }.forEach { prod ->
                borrados["$catId/productos/${prod.id}"] = null
            }
        }

        if (borrados.isNotEmpty()) categoriasRef.updateChildren(borrados)
    }

    // ── Diálogos de editar / eliminar ────────────────────

    private fun mostrarOpcionesCategoria(categoria: Categoria) {
        AlertDialog.Builder(this)
            .setTitle(categoria.nombre)
            .setItems(arrayOf("Renombrar", "Eliminar")) { _, opcion ->
                when (opcion) {
                    0 -> mostrarDialogoTexto("Renombrar categoría", "Nombre", categoria.nombre, "Guardar") { nuevo ->
                        if (nuevo != categoria.nombre && existeCategoria(nuevo)) toast("Ya existe esa categoría")
                        else editarCategoriaEnFirebase(categoria.id, nuevo)
                    }
                    1 -> confirmarEliminarCategoria(categoria)
                }
            }
            .show()
    }

    private fun confirmarEliminarCategoria(categoria: Categoria) {
        val total = categoria.productos.size
        val mensaje = if (total == 0) "¿Eliminar la categoría \"${categoria.nombre}\"?"
        else "Se eliminará \"${categoria.nombre}\" y sus $total producto(s). ¿Continuar?"

        AlertDialog.Builder(this)
            .setTitle("Eliminar categoría")
            .setMessage(mensaje)
            .setPositiveButton("Eliminar") { _, _ -> eliminarCategoriaEnFirebase(categoria.id) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarOpcionesProducto(catId: String, producto: Producto) {
        AlertDialog.Builder(this)
            .setTitle(producto.nombre)
            .setItems(arrayOf("Editar", "Eliminar")) { _, opcion ->
                when (opcion) {
                    0 -> mostrarDialogoTexto("Editar producto", "Nombre del producto", producto.nombre, "Guardar") { nuevo ->
                        editarProductoEnFirebase(catId, producto.id, nuevo)
                    }
                    1 -> eliminarProductoEnFirebase(catId, producto.id)
                }
            }
            .show()
    }

    // Diálogo reutilizable con un campo de texto
    private fun mostrarDialogoTexto(
        titulo: String,
        pista: String,
        textoInicial: String,
        textoBoton: String,
        onAceptar: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            hint = pista
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(textoInicial)
            setSelection(text.length)
        }
        val contenedor = FrameLayout(this).apply {
            setPadding(24.dp, 8.dp, 24.dp, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(titulo)
            .setView(contenedor)
            .setPositiveButton(textoBoton) { _, _ ->
                val texto = input.text.toString().trim()
                if (texto.isEmpty()) toast("El nombre no puede estar vacío")
                else onAceptar(texto)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ── UI ───────────────────────────────────────────────

    private fun reconstruirUI() {
        listaProductos.removeAllViews()

        if (categorias.isEmpty()) {
            mostrarEstadoVacio()
            return
        }

        categorias.values.sortedBy { it.nombre.lowercase() }.forEach {
            listaProductos.addView(crearVistaCategoria(it))
        }
    }

    private fun crearVistaCategoria(categoria: Categoria): LinearLayout {
        // Cada categoría es una tarjeta blanca
        val contenedor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_categoria)
            setPadding(16.dp, 12.dp, 12.dp, 8.dp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12.dp }
        }

        // Cabecera: nombre a la izquierda, contador "pendientes/total" a la derecha
        val total = categoria.productos.size
        val pendientes = categoria.productos.values.count { !it.completado }

        val cabecera = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4.dp, 4.dp, 8.dp)
            setOnLongClickListener {
                mostrarOpcionesCategoria(categoria)
                true
            }
        }

        cabecera.addView(TextView(this).apply {
            text = categoria.nombre.uppercase()
            textSize = 13f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        if (total > 0) {
            cabecera.addView(TextView(this).apply {
                text = "$pendientes/$total"
                textSize = 12f
                setTextColor(color(R.color.text_secondary))
            })
        }

        contenedor.addView(cabecera)

        if (categoria.productos.isEmpty()) {
            contenedor.addView(TextView(this).apply {
                text = "Sin productos todavía"
                setTextColor(color(R.color.text_secondary))
                setTypeface(typeface, Typeface.ITALIC)
                setPadding(4.dp, 0, 0, 8.dp)
            })
        } else {
            // Pendientes primero, completados al final; alfabético dentro de cada grupo
            categoria.productos.values
                .sortedWith(compareBy<Producto>({ it.completado }, { it.nombre.lowercase() }))
                .forEach { contenedor.addView(crearVistaProducto(categoria.id, it)) }
        }

        return contenedor
    }

    private fun crearVistaProducto(catId: String, producto: Producto): LinearLayout {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 48.dp
        }

        val checkBox = CheckBox(this).apply {
            isChecked = producto.completado
            setOnCheckedChangeListener { _, isChecked ->
                toggleProductoCompletado(catId, producto.id, isChecked)
            }
        }

        val tv = TextView(this).apply {
            text = producto.nombre
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (producto.completado) {
                paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                setTextColor(color(R.color.text_secondary))
            } else {
                setTextColor(color(R.color.text_primary))
            }
            // Tocar el texto también marca/desmarca
            setOnClickListener { checkBox.toggle() }
        }

        val abrirOpciones = View.OnLongClickListener {
            mostrarOpcionesProducto(catId, producto)
            true
        }
        tv.setOnLongClickListener(abrirOpciones)
        fila.setOnLongClickListener(abrirOpciones)

        fila.addView(checkBox)
        fila.addView(tv)

        return fila
    }

    private fun actualizarSpinner() {
        val ordenadas = categorias.values.sortedBy { it.nombre.lowercase() }
        idsSpinner = ordenadas.map { it.id }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ordenadas.map { it.nombre })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCategoria.adapter = adapter

        if (idsSpinner.isEmpty()) {
            categoriaSeleccionadaId = null
            return
        }

        // Mantenemos la categoría que estaba elegida (o la primera si ya no existe)
        val index = idsSpinner.indexOf(categoriaSeleccionadaId).takeIf { it >= 0 } ?: 0
        spinnerCategoria.setSelection(index, false)
        categoriaSeleccionadaId = idsSpinner[index]
    }

    // ── Helpers ──────────────────────────────────────────

    private fun existeCategoria(nombre: String) =
        categorias.values.any { it.nombre.equals(nombre, ignoreCase = true) }

    private fun mostrarEstadoVacio() {
        val tv = TextView(this).apply {
            text = "Todavía no hay categorías.\nPulsa \"Nueva\" para crear la primera."
            gravity = Gravity.CENTER
            setTextColor(color(R.color.text_secondary))
            setPadding(0, 48.dp, 0, 0)
        }
        listaProductos.addView(tv)
    }

    private fun mostrarInfoUsuario(tv: TextView) {
        val user = auth.currentUser
        val nombre = user?.email?.substringBefore("@") ?: "Usuario"
        tv.text = nombre
    }

    private fun ocultarTeclado(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun toast(mensaje: String) =
        Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()

    private fun irAlLogin() {
        val intent = Intent(this, LoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }
}
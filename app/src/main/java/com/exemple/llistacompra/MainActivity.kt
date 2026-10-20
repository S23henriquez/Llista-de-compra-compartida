package com.exemple.llistacompra

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
    val emoji: String = "",
    val productos: Map<String, Producto> = emptyMap()
)

// ── Activity ────────────────────────────────────────────

class MainActivity : AppCompatActivity() {

    private lateinit var listaProductos: LinearLayout
    private lateinit var spinnerCategoria: Spinner
    private lateinit var tilProducte: TextInputLayout
    private lateinit var inputProducte: TextInputEditText
    private lateinit var contenedorSugerencias: View
    private lateinit var chipsCategoria: ChipGroup

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val dbRef by lazy { FirebaseDatabase.getInstance().getReference("listas") }
    private val uid get() = auth.currentUser?.uid ?: ""
    private val categoriasRef get() = dbRef.child(uid).child("categorias")
    private val aprendidoRef get() = dbRef.child(uid).child("aprendido")

    private val categorias = mutableMapOf<String, Categoria>()

    // Estado del spinner: ids en el mismo orden que los nombres mostrados
    private var idsSpinner: List<String> = emptyList()
    private var categoriaSeleccionadaId: String? = null

    // ── Autoclasificación ──
    // Correcciones del usuario: "monster" -> "Bebidas"
    private val aprendido = mutableMapOf<String, String>()
    // Categorías posibles para lo que se está escribiendo, de más a menos probable
    private var candidatos: List<String> = emptyList()
    // Chip que ha tocado el usuario (si ha tocado alguno)
    private var chipElegido: String? = null
    // Categoría sugerida final (puede no existir todavía)
    private var sugerencia: String? = null
    // true si el usuario ha cambiado la categoría a mano: entonces no la pisamos
    private var eleccionManual = false
    private var spinnerTocado = false

    /** Dónde irá el producto. id = null significa que hay que crear la categoría. */
    private data class Destino(val nombre: String, val id: String?)

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

        val btnAfegir      = findViewById<FloatingActionButton>(R.id.btnAfegir)
        val btnLimpieza    = findViewById<Button>(R.id.btnLimpieza)
        val btnVolverLogin = findViewById<Button>(R.id.btnVolverLogin)
        val txtUsuario     = findViewById<TextView>(R.id.txtUsuario)
        val btnNuevaCat    = findViewById<Button>(R.id.btnNuevaCategoria)

        inputProducte    = findViewById(R.id.inputProducte)
        tilProducte      = findViewById(R.id.tilProducte)
        contenedorSugerencias = findViewById(R.id.contenedorSugerencias)
        chipsCategoria   = findViewById(R.id.chipsCategoria)
        spinnerCategoria = findViewById(R.id.spinnerCategoria)
        listaProductos   = findViewById(R.id.listaProductos)

        configurarSpinner()

        // Mientras escribe, sugerimos categoría
        inputProducte.doAfterTextChanged { actualizarSugerencia() }

        mostrarInfoUsuario(txtUsuario)
        cargarAprendido()
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
                tilProducte.error = "Introduce un producto"
                return@setOnClickListener
            }

            val destino = categoriaDestino()
            // Si la categoría sugerida no existe todavía, la creamos
            val catId = destino.id
                ?: agregarCategoriaEnFirebase(destino.nombre, Clasificador.emojiPara(destino.nombre))
                ?: return@setOnClickListener

            agregarProductoEnFirebase(catId, nombre)
            aprender(nombre, destino.nombre)

            // Reiniciamos para el siguiente producto
            eleccionManual = false
            chipElegido = null
            sugerencia = null
            inputProducte.text?.clear()
            ocultarTeclado(inputProducte)
        }

        btnNuevaCat.setOnClickListener { mostrarDialogoCategoria(null) }

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
            // Olvidamos también la cuenta de Google elegida, para poder escoger otra la próxima vez
            lifecycleScope.launch {
                try {
                    CredentialManager.create(this@MainActivity)
                        .clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w("Logout", "No se pudo limpiar la sesión de Google", e)
                }
                irAlLogin()
            }
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

                    val emoji = catSnap.child("emoji").getValue(String::class.java).orEmpty()
                    categorias[catId] = Categoria(catId, nombre, emoji, productos)
                }

                reconstruirUI()
                actualizarSpinner()
                // Por si hay algo escrito y acaba de aparecer su categoría
                if (!inputProducte.text.isNullOrBlank()) actualizarSugerencia()
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

    // ── Autoclasificación ────────────────────────────────

    /** Detecta cuándo es el USUARIO quien cambia el spinner (y no nosotros por código). */
    @SuppressLint("ClickableViewAccessibility")
    private fun configurarSpinner() {
        spinnerCategoria.setOnTouchListener { _, evento ->
            if (evento.action == MotionEvent.ACTION_UP) spinnerTocado = true
            false
        }

        spinnerCategoria.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val elegida = idsSpinner.getOrNull(position)
                categoriaSeleccionadaId = elegida

                // El usuario ha elegido a mano algo distinto a lo sugerido -> se respeta
                if (spinnerTocado && elegida != idSugerido()) {
                    eleccionManual = true
                    pintarChips()   // ya ha decidido: escondemos los chips
                }
                spinnerTocado = false

                actualizarAyuda()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Se llama cada vez que cambia el texto del producto. */
    private fun actualizarSugerencia() {
        tilProducte.error = null
        val nombre = inputProducte.text?.toString()?.trim().orEmpty()

        if (nombre.isEmpty()) {
            candidatos = emptyList()
            chipElegido = null
            sugerencia = null
            eleccionManual = false
            pintarChips()
            actualizarAyuda()
            return
        }

        candidatos = Clasificador.candidatos(nombre, aprendido)

        // Si había tocado un chip y sigue siendo válido, lo respetamos
        val elegido = chipElegido
        if (elegido != null && candidatos.none { Clasificador.mismoNombre(it, elegido) }) chipElegido = null
        sugerencia = chipElegido ?: candidatos.firstOrNull()

        // Si no la ha cambiado a mano, movemos el spinner a la categoría sugerida
        if (!eleccionManual) idSugerido()?.let { seleccionarEnSpinner(it) }

        pintarChips()
        actualizarAyuda()
    }

    /** Muestra un chip por categoría posible cuando hay más de una. */
    private fun pintarChips() {
        val mostrar = candidatos.size >= 2 && !eleccionManual
        contenedorSugerencias.visibility = if (mostrar) View.VISIBLE else View.GONE
        chipsCategoria.removeAllViews()
        if (!mostrar) return

        candidatos.forEach { nombre ->
            val chip = layoutInflater.inflate(R.layout.item_chip_categoria, chipsCategoria, false) as Chip
            chip.id = View.generateViewId()
            chip.text = nombre
            chip.isChecked = sugerencia?.let { Clasificador.mismoNombre(it, nombre) } == true
            chip.setOnClickListener { elegirChip(nombre) }
            chipsCategoria.addView(chip)
        }
    }

    /** El usuario toca un chip: esa pasa a ser la categoría de destino. */
    private fun elegirChip(nombre: String) {
        chipElegido = nombre
        sugerencia = nombre
        eleccionManual = false
        idSugerido()?.let { seleccionarEnSpinner(it) }
        actualizarAyuda()
    }

    /** Texto de ayuda bajo el campo: dónde se va a guardar el producto. */
    private fun actualizarAyuda() {
        val nombre = inputProducte.text?.toString()?.trim().orEmpty()
        if (nombre.isEmpty()) {
            tilProducte.helperText = null
            return
        }

        val destino = categoriaDestino()
        val etiqueta = when {
            destino.id == null -> " (nueva categoría)"
            chipElegido != null -> ""
            !eleccionManual && sugerencia != null -> " · sugerida"
            else -> ""
        }
        tilProducte.helperText = "Se añadirá a: ${destino.nombre}$etiqueta"
    }

    private fun categoriaDestino(): Destino {
        // 1. La sugerencia, si el usuario no ha elegido otra cosa
        if (!eleccionManual) {
            sugerencia?.let { s ->
                val existente = buscarCategoriaPorNombre(s)
                return Destino(existente?.nombre ?: s, existente?.id)
            }
        }
        // 2. Lo que haya en el spinner
        categoriaSeleccionadaId?.let { id -> categorias[id]?.let { return Destino(it.nombre, it.id) } }
        // 3. Si no hay ninguna categoría: "Otros"
        return Destino(Clasificador.OTROS, buscarCategoriaPorNombre(Clasificador.OTROS)?.id)
    }

    private fun idSugerido(): String? = sugerencia?.let { buscarCategoriaPorNombre(it)?.id }

    private fun buscarCategoriaPorNombre(nombre: String): Categoria? =
        categorias.values.firstOrNull { Clasificador.mismoNombre(it.nombre, nombre) }

    private fun seleccionarEnSpinner(catId: String) {
        val index = idsSpinner.indexOf(catId)
        if (index >= 0 && spinnerCategoria.selectedItemPosition != index) {
            spinnerCategoria.setSelection(index)
        }
        categoriaSeleccionadaId = catId
    }

    /**
     * Guarda la corrección del usuario para la próxima vez.
     * Si coincide con lo que ya diría el diccionario, no hace falta guardarla.
     */
    private fun aprender(producto: String, categoria: String) {
        if (uid.isEmpty()) return
        val clave = Clasificador.clave(producto)
        if (clave.isEmpty()) return

        val porDiccionario = Clasificador.sugerirPorDiccionario(producto)
        if (porDiccionario != null && Clasificador.mismoNombre(porDiccionario, categoria)) {
            if (aprendido.remove(clave) != null) aprendidoRef.child(clave).removeValue()
            return
        }

        if (aprendido[clave] != categoria) {
            aprendido[clave] = categoria
            aprendidoRef.child(clave).setValue(categoria)
        }
    }

    private fun cargarAprendido() {
        if (uid.isEmpty()) return
        aprendidoRef.get().addOnSuccessListener { snapshot ->
            snapshot.children.forEach { hijo ->
                val clave = hijo.key ?: return@forEach
                val categoria = hijo.getValue(String::class.java) ?: return@forEach
                aprendido[clave] = categoria
            }
        }
    }

    // ── CRUD ─────────────────────────────────────────────

    /** Crea la categoría, la deja seleccionada y devuelve su id. */
    private fun agregarCategoriaEnFirebase(nombre: String, emoji: String): String? {
        if (uid.isEmpty()) return null
        val catId = categoriasRef.push().key ?: return null
        categoriaSeleccionadaId = catId
        categoriasRef.child(catId).setValue(mapOf("nombre" to nombre, "emoji" to emoji))
        return catId
    }

    /** Mueve un producto de una categoría a otra en una sola escritura. */
    private fun moverProducto(origenId: String, destinoId: String, producto: Producto) {
        if (uid.isEmpty() || origenId == destinoId) return
        val cambios = mapOf<String, Any?>(
            "$origenId/productos/${producto.id}" to null,
            "$destinoId/productos/${producto.id}" to mapOf(
                "nombre" to producto.nombre,
                "completado" to producto.completado
            )
        )
        categoriasRef.updateChildren(cambios)
    }

    /** Cambia nombre y emoji sin tocar los productos de la categoría. */
    private fun editarCategoriaEnFirebase(catId: String, nuevoNombre: String, nuevoEmoji: String) {
        if (uid.isEmpty()) return
        categoriasRef.child(catId).updateChildren(mapOf("nombre" to nuevoNombre, "emoji" to nuevoEmoji))
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
            .setItems(arrayOf("Editar nombre e icono", "Eliminar")) { _, opcion ->
                when (opcion) {
                    0 -> mostrarDialogoCategoria(categoria)
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
            .setItems(arrayOf("Editar", "Cambiar de categoría", "Eliminar")) { _, opcion ->
                when (opcion) {
                    0 -> mostrarDialogoTexto("Editar producto", "Nombre del producto", producto.nombre, "Guardar") { nuevo ->
                        editarProductoEnFirebase(catId, producto.id, nuevo)
                    }
                    1 -> elegirNuevaCategoria(catId, producto)
                    2 -> eliminarProductoEnFirebase(catId, producto.id)
                }
            }
            .show()
    }

    /** Lista de categorías para mover el producto. La app aprende la elección. */
    private fun elegirNuevaCategoria(catActualId: String, producto: Producto) {
        val otras = categorias.values
            .filter { it.id != catActualId }
            .sortedBy { it.nombre.lowercase() }

        if (otras.isEmpty()) {
            toast("No hay otras categorías. Crea una con \"Nueva\"")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Mover \"${producto.nombre}\" a…")
            .setItems(otras.map { it.nombre }.toTypedArray()) { _, i ->
                val destino = otras[i]
                moverProducto(catActualId, destino.id, producto)
                aprender(producto.nombre, destino.nombre)
                toast("Movido a ${destino.nombre}. Lo recordaré para la próxima vez")
            }
            .setNegativeButton("Cancelar", null)
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

        // Emoji: al tocarlo se abre el editor de la categoría
        cabecera.addView(TextView(this).apply {
            text = emojiDe(categoria)
            textSize = 20f
            setPadding(0, 0, 8.dp, 0)
            contentDescription = "Cambiar icono de ${categoria.nombre}"
            setOnClickListener { mostrarDialogoCategoria(categoria) }
        })

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

        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            ordenadas.map { "${emojiDe(it)}  ${it.nombre}" }
        )
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

    /** ¿Hay otra categoría con ese nombre? (se excluye la que se está editando) */
    private fun existeOtraCategoria(nombre: String, exceptoId: String?) =
        categorias.values.any { it.id != exceptoId && Clasificador.mismoNombre(it.nombre, nombre) }

    /** Emoji guardado, o uno por defecto según el nombre (para categorías antiguas sin emoji). */
    private fun emojiDe(categoria: Categoria) =
        categoria.emoji.ifBlank { Clasificador.emojiPara(categoria.nombre) }

    // ── Diálogo de categoría (crear / editar) ────────────

    /**
     * Si [existente] es null crea una categoría nueva; si no, la edita.
     * Mientras no se toque el emoji, se va sugiriendo uno según el nombre que se escribe.
     */
    private fun mostrarDialogoCategoria(existente: Categoria?) {
        val vista = layoutInflater.inflate(R.layout.dialogo_categoria, null)
        val txtEmoji = vista.findViewById<TextView>(R.id.txtEmojiElegido)
        val tilNombre = vista.findViewById<TextInputLayout>(R.id.tilNombreCategoria)
        val inputNombre = vista.findViewById<TextInputEditText>(R.id.inputNombreCategoria)
        val grid = vista.findViewById<GridView>(R.id.gridEmojis)
        val tilOtro = vista.findViewById<TextInputLayout>(R.id.tilOtroEmoji)
        val inputOtro = vista.findViewById<TextInputEditText>(R.id.inputOtroEmoji)

        var emojiElegido = existente?.let { emojiDe(it) } ?: Clasificador.EMOJI_POR_DEFECTO
        var emojiTocado = existente != null   // al editar no cambiamos el emoji solo

        val adapter = EmojiAdapter(Clasificador.EMOJIS_DISPONIBLES, emojiElegido)
        grid.adapter = adapter

        fun elegir(emoji: String, aMano: Boolean) {
            emojiElegido = emoji
            if (aMano) emojiTocado = true
            txtEmoji.text = emoji
            adapter.seleccionar(emoji)
        }

        txtEmoji.text = emojiElegido
        inputNombre.setText(existente?.nombre.orEmpty())
        inputNombre.setSelection(inputNombre.text?.length ?: 0)

        // Sugerencia automática de emoji mientras escribe el nombre
        inputNombre.doAfterTextChanged { texto ->
            tilNombre.error = null
            if (!emojiTocado) elegir(Clasificador.emojiPara(texto.toString()), aMano = false)
        }

        grid.setOnItemClickListener { _, _, posicion, _ ->
            inputOtro.text?.clear()
            elegir(adapter.getItem(posicion), aMano = true)
        }

        // Cualquier emoji escrito con el teclado
        inputOtro.doAfterTextChanged { texto ->
            val escrito = texto?.toString().orEmpty()
            if (escrito.isBlank()) {
                tilOtro.error = null
                return@doAfterTextChanged
            }
            val emoji = primerEmoji(escrito)
            if (emoji == null) {
                tilOtro.error = "Eso no es un emoji"
            } else {
                tilOtro.error = null
                elegir(emoji, aMano = true)
            }
        }

        val dialogo = AlertDialog.Builder(this)
            .setTitle(if (existente == null) "Nueva categoría" else "Editar categoría")
            .setView(vista)
            .setPositiveButton(if (existente == null) "Crear" else "Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()

        // Validamos sin cerrar el diálogo si hay errores
        dialogo.setOnShowListener {
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nombre = inputNombre.text?.toString()?.trim().orEmpty()
                when {
                    nombre.isEmpty() -> tilNombre.error = "Ponle un nombre"
                    existeOtraCategoria(nombre, existente?.id) -> tilNombre.error = "Ya existe esa categoría"
                    else -> {
                        if (existente == null) agregarCategoriaEnFirebase(nombre, emojiElegido)
                        else editarCategoriaEnFirebase(existente.id, nombre, emojiElegido)
                        dialogo.dismiss()
                    }
                }
            }
        }

        dialogo.show()
    }

    /**
     * Devuelve el primer emoji del texto, o null si lo primero es una letra/número.
     * Usa el BreakIterator de ICU para no partir emojis compuestos (👨‍👩‍👧, 🇪🇸...).
     */
    private fun primerEmoji(texto: String): String? {
        val limpio = texto.trim()
        if (limpio.isEmpty()) return null

        val iterador = android.icu.text.BreakIterator.getCharacterInstance()
        iterador.setText(limpio)
        val fin = iterador.next()
        val primero = if (fin > 0) limpio.substring(0, fin) else limpio

        val codigo = primero.codePointAt(0)
        return if (Character.isLetterOrDigit(codigo) || Character.isWhitespace(codigo)) null else primero
    }

    /** Adaptador de la cuadrícula: marca con un recuadro el emoji seleccionado. */
    private inner class EmojiAdapter(
        private val emojis: List<String>,
        private var seleccionado: String
    ) : BaseAdapter() {

        fun seleccionar(emoji: String) {
            seleccionado = emoji
            notifyDataSetChanged()
        }

        override fun getCount() = emojis.size
        override fun getItem(position: Int) = emojis[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val vista = (convertView as? TextView)
                ?: layoutInflater.inflate(R.layout.item_emoji, parent, false) as TextView
            val emoji = emojis[position]
            vista.text = emoji
            if (emoji == seleccionado) vista.setBackgroundResource(R.drawable.bg_emoji_seleccionado)
            else vista.background = null
            return vista
        }
    }

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
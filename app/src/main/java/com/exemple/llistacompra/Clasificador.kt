package com.exemple.llistacompra

import java.text.Normalizer

/**
 * Sugiere la(s) categoría(s) de un producto a partir de su nombre.
 *
 *  1. Primero lo que el usuario ha "enseñado" (sus correcciones guardadas).
 *  2. Después el diccionario de palabras clave.
 *  3. Si el producto encaja en varias categorías devuelve todas, de más a menos probable,
 *     para que el usuario elija: "patatas" -> [Verdura, Snacks].
 *
 * Las palabras clave se escriben en minúscula, sin tildes (pero CON ñ) y en singular:
 * "pañal" reconoce "Pañales", "pañal" y también "panales" (escrito sin ñ).
 */
object Clasificador {

    const val OTROS = "Otros"
    const val EMOJI_POR_DEFECTO = "🛒"

    /** Emoji por defecto de cada categoría del diccionario. */
    private val emojisCategoria: Map<String, String> = mapOf(
        "Fruta" to "🍎",
        "Verdura" to "🥦",
        "Carne" to "🥩",
        "Pescado" to "🐟",
        "Lácteos y huevos" to "🥛",
        "Panadería" to "🥖",
        "Desayuno y dulces" to "🍪",
        "Despensa" to "🥫",
        "Bebidas" to "🥤",
        "Congelados" to "🧊",
        "Snacks" to "🍿",
        "Limpieza" to "🧽",
        "Higiene" to "🧴",
        "Mascotas" to "🐾",
        OTROS to EMOJI_POR_DEFECTO
    )

    /** Emojis que se ofrecen en la cuadrícula al crear/editar una categoría. */
    val EMOJIS_DISPONIBLES: List<String> = listOf(
        "🛒", "🍎", "🍌", "🍊", "🍇", "🍓", "🍉", "🍍", "🥑", "🍋",
        "🥕", "🥦", "🥬", "🍅", "🥔", "🌽", "🍄", "🥒", "🌶️", "🥗",
        "🥩", "🍗", "🥓", "🌭", "🍔", "🐟", "🦐", "🦑", "🥚", "🥛",
        "🧀", "🍞", "🥐", "🥖", "🥨", "🍪", "🍫", "🍰", "🍯", "☕",
        "🍵", "🍚", "🍝", "🥫", "🧂", "🥤", "🍺", "🍷", "🧊", "🍦",
        "🍕", "🍿", "🥜", "🍬", "🧽", "🧴", "🧼", "🧻", "🧹", "🧺",
        "🐾", "👶", "💊", "🏠", "🎉", "🎁", "📦", "⭐"
    )

    /**
     * Emoji para un nombre de categoría:
     *  1. Si es una del diccionario (o empieza igual: "Frutas y verduras" -> Fruta) -> su emoji.
     *  2. Si el nombre parece un producto ("Patatas") -> el emoji de su categoría.
     *  3. Si no -> 🛒
     */
    fun emojiPara(nombreCategoria: String): String {
        emojisCategoria.entries.firstOrNull { mismoNombre(it.key, nombreCategoria) }?.let { return it.value }

        val primera = palabrasDe(nombreCategoria).firstOrNull() ?: return EMOJI_POR_DEFECTO
        emojisCategoria.entries.firstOrNull { (categoria, _) ->
            palabrasDe(categoria).firstOrNull()?.let { coincide(primera, it) } == true
        }?.let { return it.value }

        candidatos(nombreCategoria).firstOrNull()?.let { categoria ->
            emojisCategoria[categoria]?.let { return it }
        }
        return EMOJI_POR_DEFECTO
    }

    private val diccionario: Map<String, List<String>> = mapOf(
        "Fruta" to listOf(
            "manzana", "platano", "banana", "naranja", "mandarina", "pera", "uva", "fresa",
            "kiwi", "melon", "sandia", "piña", "mango", "limon", "lima", "cereza", "melocoton",
            "albaricoque", "ciruela", "aguacate", "frambuesa", "arandano", "higo", "granada",
            "coco", "papaya", "nectarina", "fruta"
        ),
        "Verdura" to listOf(
            "tomate", "lechuga", "cebolla", "ajo", "patata", "zanahoria", "pimiento", "pepino",
            "calabacin", "berenjena", "brocoli", "coliflor", "espinaca", "judia verde", "puerro",
            "apio", "champiñon", "seta", "calabaza", "alcachofa", "esparrago", "col", "rucula",
            "maiz", "guisante", "canonigo", "boniato", "verdura", "ensalada", "cebolleta"
        ),
        "Carne" to listOf(
            "pollo", "ternera", "cerdo", "cordero", "pavo", "carne", "carne picada", "hamburguesa",
            "salchicha", "chorizo", "jamon", "lomo", "costilla", "filete", "bacon", "beicon",
            "pechuga", "muslo", "fuet", "salchichon", "mortadela", "embutido", "conejo", "albondiga"
        ),
        "Pescado" to listOf(
            "salmon", "atun", "merluza", "bacalao", "sardina", "gamba", "langostino", "mejillon",
            "calamar", "pulpo", "dorada", "lubina", "boqueron", "anchoa", "sepia", "almeja",
            "pescado", "marisco", "surimi", "rape", "trucha", "caballa"
        ),
        "Lácteos y huevos" to listOf(
            "leche", "yogur", "queso", "mantequilla", "nata", "kefir", "natilla", "flan",
            "cuajada", "batido", "requeson", "mozzarella", "huevo", "margarina"
        ),
        "Panadería" to listOf(
            "pan", "barra de pan", "baguette", "croissant", "magdalena", "bolleria",
            "pan de molde", "pan tostado", "donut", "bizcocho", "tortilla de trigo", "picos",
            "ensaimada", "coca"
        ),
        "Desayuno y dulces" to listOf(
            "galleta", "cereal", "cacao", "colacao", "nesquik", "mermelada", "miel", "chocolate",
            "cafe", "te", "infusion", "manzanilla", "tila", "crema de cacao", "nocilla", "nutella"
        ),
        "Despensa" to listOf(
            "arroz", "pasta", "macarron", "espagueti", "fideo", "harina", "azucar", "sal",
            "aceite", "vinagre", "legumbre", "lenteja", "garbanzo", "alubia", "tomate frito",
            "atun en lata", "conserva", "especia", "pimienta", "oregano", "caldo", "salsa",
            "ketchup", "mayonesa", "mostaza", "levadura", "pan rallado", "sopa", "pure"
        ),
        "Bebidas" to listOf(
            "agua", "refresco", "cocacola", "coca cola", "fanta", "sprite", "zumo", "cerveza",
            "vino", "cava", "gaseosa", "tonica", "aquarius", "nestea", "red bull", "bebida",
            "sidra", "licor", "ron", "ginebra", "whisky", "vodka"
        ),
        "Congelados" to listOf(
            "congelado", "helado", "pizza", "croqueta", "nugget", "hielo", "lasaña",
            "verdura congelada", "pescado congelado", "patatas congeladas"
        ),
        "Snacks" to listOf(
            "patatas fritas", "chips", "aceituna", "fruto seco", "almendra", "nuez", "cacahuete",
            "pipa", "palomita", "gominola", "chuche", "nacho", "pistacho", "anacardo", "snack"
        ),
        "Limpieza" to listOf(
            "lejia", "detergente", "suavizante", "friegasuelos", "lavavajillas", "fregona",
            "estropajo", "bayeta", "papel de cocina", "bolsa de basura", "limpiacristales",
            "amoniaco", "servilleta", "papel de aluminio", "papel film", "ambientador", "insecticida"
        ),
        "Higiene" to listOf(
            "champu", "gel", "jabon", "desodorante", "pasta de dientes", "dentifrico",
            "cepillo de dientes", "papel higienico", "compresa", "tampon", "cuchilla",
            "maquinilla", "crema", "colonia", "algodon", "pañal", "toallita", "acondicionador",
            "enjuague", "colutorio", "pañuelo", "bastoncillo"
        ),
        "Mascotas" to listOf(
            "pienso", "comida de perro", "comida de gato", "arena de gato", "comida para perro",
            "comida para gato", "premio para perro"
        )
    )

    /** Todas las palabras clave ya troceadas: ("Snacks", ["patatas", "fritas"]), ... */
    private val claves: List<Pair<String, List<String>>> = diccionario
        .flatMap { (categoria, palabras) -> palabras.map { categoria to palabrasDe(it) } }

    /** Una palabra clave que encaja con el producto. */
    private data class Coincidencia(
        val categoria: String,
        val completa: Boolean,  // true: "patatas" ↔ "patata" | false: "patatas" ↔ "patatas fritas"
        val inicio: Int,        // posición en el producto
        val longitud: Int       // nº de palabras que ocupa
    ) {
        val fin get() = inicio + longitud
    }

    /**
     * Categorías posibles, de más a menos probable (máximo [max]).
     *
     * Orden:
     *  1. Lo aprendido del usuario.
     *  2. Coincidencias completas: más palabras primero, y a igualdad, la que sale antes
     *     ("zumo de naranja" -> Bebidas, Fruta).
     *  3. Coincidencias parciales: el producto es el principio de una clave más larga
     *     ("patatas" también puede ser "patatas fritas" -> Snacks).
     *
     * Si una clave queda tapada por otra más larga ("pan" dentro de "pan rallado"), se descarta.
     */
    fun candidatos(producto: String, aprendido: Map<String, String> = emptyMap(), max: Int = 3): List<String> {
        val palabrasProducto = palabrasDe(producto)
        if (palabrasProducto.isEmpty()) return emptyList()

        val completas = mutableListOf<Coincidencia>()
        val parciales = mutableListOf<Coincidencia>()

        for ((categoria, palabrasClave) in claves) {
            val inicio = posicionDe(palabrasProducto, palabrasClave)
            if (inicio >= 0) {
                completas += Coincidencia(categoria, true, inicio, palabrasClave.size)
            } else if (esPrincipioDe(palabrasProducto, palabrasClave)) {
                parciales += Coincidencia(categoria, false, 0, palabrasProducto.size)
            }
        }

        // Quitamos las coincidencias tapadas por otra más larga
        val utiles = completas.filter { c ->
            completas.none { otra ->
                otra.longitud > c.longitud && otra.inicio <= c.inicio && c.fin <= otra.fin
            }
        }

        val ordenadas = utiles.sortedWith(
            compareByDescending<Coincidencia> { it.longitud }.thenBy { it.inicio }
        ) + parciales

        val resultado = mutableListOf<String>()
        aprendido[clave(producto)]?.let { resultado += it }
        for (c in ordenadas) {
            if (resultado.none { mismoNombre(it, c.categoria) }) resultado += c.categoria
        }
        return resultado.take(max)
    }

    /** La sugerencia principal (o null si no hay ninguna). */
    fun sugerir(producto: String, aprendido: Map<String, String>): String? =
        candidatos(producto, aprendido).firstOrNull()

    /** Solo el diccionario (sin tener en cuenta lo aprendido). */
    fun sugerirPorDiccionario(producto: String): String? =
        candidatos(producto).firstOrNull()

    /** ¿Dos nombres de categoría son "el mismo"? (ignora mayúsculas, tildes, ñ/n y la "s" final) */
    fun mismoNombre(a: String, b: String) =
        sinEñe(normalizar(a)).removeSuffix("s") == sinEñe(normalizar(b)).removeSuffix("s")

    /** Clave para guardar en Firebase (no admite . # $ [ ] /). Conserva la ñ. */
    fun clave(producto: String): String =
        palabrasDe(producto).joinToString(" ").replace(Regex("[.#$\\[\\]/]"), "_")

    // ── Internos ───────────────────────────────────────────

    /**
     * minúsculas, sin tildes, sin espacios dobles... pero CONSERVANDO la ñ.
     * "  Plátanos " -> "platanos"   |   "PAÑALES" -> "pañales"
     */
    private fun normalizar(texto: String): String {
        val separado = Normalizer.normalize(texto.lowercase().trim(), Normalizer.Form.NFD)
        return separado
            .replace("ñ", "ñ")            // la ñ se separa en n + ~ : la volvemos a juntar
            .replace(Regex("\\p{Mn}+"), "")      // quitamos el resto de tildes (á, é, ü...)
            .replace(Regex("\\s+"), " ")
    }

    private fun sinEñe(texto: String) = texto.replace('ñ', 'n')

    private fun palabrasDe(texto: String): List<String> =
        normalizar(texto).split(Regex("[^a-z0-9ñ]+")).filter { it.isNotEmpty() }

    /** Posición donde aparecen seguidas las palabras de la clave dentro del producto, o -1. */
    private fun posicionDe(producto: List<String>, clave: List<String>): Int {
        if (clave.isEmpty() || clave.size > producto.size) return -1
        for (inicio in 0..producto.size - clave.size) {
            if (clave.indices.all { i -> coincide(producto[inicio + i], clave[i]) }) return inicio
        }
        return -1
    }

    /** ¿El producto entero es el principio de una clave más larga? "patatas" ↔ "patatas fritas" */
    private fun esPrincipioDe(producto: List<String>, clave: List<String>): Boolean =
        producto.size < clave.size && producto.indices.all { i -> coincide(producto[i], clave[i]) }

    /**
     * ¿Una palabra del producto coincide con una de la clave?
     *  - singular y plural: "manzana"/"manzanas", "melon"/"melones", "nuez"/"nueces"
     *  - con o sin ñ: "pañal" también acepta "panal(es)" por si el teclado no tiene ñ
     */
    private fun coincide(palabra: String, clave: String): Boolean {
        if (esFormaDe(palabra, clave)) return true
        return esFormaDe(sinEñe(palabra), sinEñe(clave))
    }

    private fun esFormaDe(palabra: String, clave: String): Boolean =
        palabra == clave ||
                palabra == clave + "s" ||
                palabra == clave + "es" ||
                (clave.endsWith("z") && palabra == clave.dropLast(1) + "ces")
}
package com.ayat64.pricefinder

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.LocalLayoutDirection
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalLayoutDirection

class MainActivity : ComponentActivity() {
    private lateinit var repo: PriceRepository

    private val pickPdfs = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            lifecycleScope.launch {
                repo.addFiles(uris)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = PriceRepository(applicationContext)
        setContent {
            MaterialTheme {
                PriceFinderScreen(repo) { pickPdfs.launch(arrayOf("application/pdf")) }
            }
        }
    }
}

data class PdfDoc(val id: Long, val name: String, val path: String, val products: Int)
data class Product(
    val id: Long,
    val docId: Long,
    val name: String,
    val code: String,
    val description: String,
    val specs: String,
    val price: String,
    val page: Int,
    val docName: String = ""
)

class PriceRepository(private val context: android.content.Context) {
    private val db = PriceDb(context)
    var documents by mutableStateOf(db.documents())
        private set
    var allProducts by mutableStateOf(db.products())
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun clearMessage() { message = null }

    suspend fun addFiles(uris: List<Uri>) {
        busy = true
        message = "در حال خواندن و ساختن فهرست جستجو..."
        try {
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    val name = queryName(uri) ?: "قیمت‌نامه.pdf"
                    val safe = System.currentTimeMillis().toString() + "_" +
                            name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val target = java.io.File(context.filesDir, "pdfs/$safe")
                    target.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@forEach

                    val docId = db.insertDocument(name, target.absolutePath)
                    val parsed = PdfEngine.extractAndParse(context, target)
                    db.insertProducts(docId, parsed)
                }
            }
            documents = db.documents()
            allProducts = db.products()
            message = "آماده است؛ ${allProducts.size} قلم کالا در فهرست جستجو قرار گرفت."
        } catch (e: Exception) {
            message = "خطا در پردازش فایل: ${e.message ?: "نامشخص"}"
        } finally {
            busy = false
        }
    }

    fun delete(doc: PdfDoc) {
        db.deleteDocument(doc.id)
        runCatching { java.io.File(doc.path).delete() }
        documents = db.documents()
        allProducts = db.products()
    }

    private fun queryName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PriceFinderScreen(repo: PriceRepository, onAdd: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(query, repo.allProducts) {
        SmartSearch.search(repo.allProducts, query).take(100)
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("جستجوی هوشمند قیمت ابزار", fontWeight = FontWeight.Bold)
                            Text("${repo.documents.size} فایل • ${repo.allProducts.size} قلم", fontSize = 12.sp)
                        }
                    }
                )
            }
        ) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    label = { Text("نام، شرح، کد، مدل یا مشخصات کالا") },
                    placeholder = { Text("مثلاً 8615 یا دریل شارژی یا پیچ‌گوشتی") }
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAdd, modifier = Modifier.weight(1f)) {
                        Text("＋ افزودن PDF")
                    }
                    OutlinedButton(
                        onClick = { query = "" },
                        modifier = Modifier.weight(.55f),
                        enabled = query.isNotEmpty()
                    ) { Text("پاک کردن") }
                }
                if (repo.busy) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                repo.message?.let {
                    Text(it, Modifier.padding(vertical = 6.dp), fontSize = 13.sp)
                }

                Text(
                    if (query.isBlank()) "فایل‌های اضافه‌شده"
                    else "نتایج جستجو (${results.size})",
                    Modifier.padding(vertical = 8.dp),
                    fontWeight = FontWeight.Bold
                )

                if (query.isBlank()) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(repo.documents, key = { it.id }) { doc ->
                            DocumentCard(doc) { repo.delete(doc) }
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(results, key = { it.product.id }) { hit ->
                            ProductCard(hit.product, hit.score)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentCard(doc: PdfDoc, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(doc.name, fontWeight = FontWeight.SemiBold)
                Text("${doc.products} قلم استخراج‌شده", fontSize = 12.sp)
            }
            TextButton(onClick = onDelete) { Text("حذف") }
        }
    }
}

@Composable
private fun ProductCard(product: Product, score: Int) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    product.name.ifBlank { "کالای بدون نام" },
                    Modifier.weight(1f),
                    fontWeight = FontWeight.Bold
                )
                if (product.price.isNotBlank()) {
                    Text(product.price, fontWeight = FontWeight.Bold)
                }
            }
            if (product.code.isNotBlank()) Text("کد کالا: ${product.code}", fontSize = 13.sp)
            if (product.description.isNotBlank()) Text("شرح: ${product.description}", fontSize = 13.sp)
            if (product.specs.isNotBlank()) Text("مشخصات: ${product.specs}", fontSize = 13.sp)
            Text("منبع: ${product.docName}  • صفحه ${product.page}  • امتیاز تطبیق $score",
                fontSize = 11.sp)
        }
    }
}


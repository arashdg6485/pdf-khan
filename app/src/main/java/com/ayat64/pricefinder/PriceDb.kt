package com.ayat64.pricefinder

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues

class PriceDb(context: Context) : SQLiteOpenHelper(context, "pricefinder.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE documents(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            path TEXT NOT NULL
        )""")
        db.execSQL("""CREATE TABLE products(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            doc_id INTEGER NOT NULL,
            name TEXT,
            code TEXT,
            description TEXT,
            specs TEXT,
            price TEXT,
            page INTEGER,
            search_text TEXT,
            FOREIGN KEY(doc_id) REFERENCES documents(id) ON DELETE CASCADE
        )""")
        db.execSQL("CREATE INDEX idx_products_doc ON products(doc_id)")
        db.execSQL("CREATE INDEX idx_products_code ON products(code)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insertDocument(name: String, path: String): Long {
        val v = ContentValues().apply { put("name", name); put("path", path) }
        return writableDatabase.insert("documents", null, v)
    }

    fun insertProducts(docId: Long, items: List<ProductDraft>) {
        writableDatabase.beginTransaction()
        try {
            items.forEach { p ->
                val v = ContentValues().apply {
                    put("doc_id", docId); put("name", p.name); put("code", p.code)
                    put("description", p.description); put("specs", p.specs)
                    put("price", p.price); put("page", p.page)
                    put("search_text", listOf(p.name,p.code,p.description,p.specs).joinToString(" "))
                }
                writableDatabase.insert("products", null, v)
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun documents(): List<PdfDoc> {
        val out = mutableListOf<PdfDoc>()
        readableDatabase.rawQuery("""
            SELECT d.id,d.name,d.path,COUNT(p.id)
            FROM documents d LEFT JOIN products p ON p.doc_id=d.id
            GROUP BY d.id ORDER BY d.id DESC
        """, null).use { c ->
            while (c.moveToNext()) out += PdfDoc(c.getLong(0),c.getString(1),c.getString(2),c.getInt(3))
        }
        return out
    }

    fun products(): List<Product> {
        val out = mutableListOf<Product>()
        readableDatabase.rawQuery("""
            SELECT p.id,p.doc_id,p.name,p.code,p.description,p.specs,p.price,p.page,d.name
            FROM products p JOIN documents d ON d.id=p.doc_id ORDER BY p.id DESC
        """, null).use { c ->
            while (c.moveToNext()) out += Product(
                c.getLong(0),c.getLong(1),c.getString(2)?:"",c.getString(3)?:"",
                c.getString(4)?:"",c.getString(5)?:"",c.getString(6)?:"",c.getInt(7),c.getString(8)?:""
            )
        }
        return out
    }

    fun deleteDocument(id: Long) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("products","doc_id=?",arrayOf(id.toString()))
            writableDatabase.delete("documents","id=?",arrayOf(id.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
}

data class ProductDraft(
    val name: String,
    val code: String,
    val description: String,
    val specs: String,
    val price: String,
    val page: Int
)

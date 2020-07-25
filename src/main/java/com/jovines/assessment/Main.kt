@file:Suppress("UNCHECKED_CAST")

package com.jovines.assessment

import com.jovines.assessment.entity.Compare
import com.jovines.assessment.entity.Repeat
import com.jovines.assessment.util.CosineSimilarity
import com.jovines.assessment.util.createParentFolder
import io.reactivex.rxjava3.core.Observable
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.xssf.usermodel.XSSFCell
import org.apache.poi.xssf.usermodel.XSSFRichTextString
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.*
import java.util.regex.Pattern

const val dataBasePath = "assessmentData"
const val codesPath = "$dataBasePath/codes"
const val excelOutputPath = "$dataBasePath/excel"
const val cacheDataPath = "$dataBasePath/cache"

fun main(args: Array<String>) {
    val de = File(codesPath)
    if (!de.exists()) {
        de.mkdirs()
        throw FileNotFoundException(
            "同级目录下没发现 assessmentData/codes目录,不出意外现在已经为你创建，\n" +
                    "请把要查重的java或者kt写的仓库直接克隆或者下载到codes目录，并最好不要更改仓库名(用于保证github爬虫对比不对比原仓库，当然，本地互查随意)\n"
        )
    }
    val filter = de.listFiles()?.filter { it.isDirectory }
    filter?.forEach { file: File ->
        checkRepeat(file)
    }
}

/**
 * 找到目录中的所有的java或者kt文件，并存放在传入的list中
 * @param mutableList 存储一个检查仓库中所有kt和java的list
 * @param file 文件咯，可以是文件夹，可以是文件，如果判断是文件且是java或kt文件则会存到list中
 *             若是目录，则会向下遍历
 */
fun findKtOrJavaFile(mutableList: MutableList<File>, file: File) {
    if (file.isDirectory) {
        file.listFiles()?.forEach {
            findKtOrJavaFile(mutableList, it)
        }
    } else if (file.name.matches(Regex(".+\\.kt"))) {
        if (!file.name.matches(Regex(".+Test\\.kt")))
            mutableList.add(file)
    } else if (file.name.matches(Regex(".+\\.java"))) {
        if (!file.name.matches(Regex(".+Test\\.java")))
            mutableList.add(file)
    }
}

/**
 * 主方法，用来检查一个目录中是否有重复
 * @param file 一个文件夹，不可是一个文件，会报错，但是不会退出
 */
fun checkRepeat(file: File) {
    var mutableList = mutableListOf<Compare>()
    if (File("$cacheDataPath/${file.name}-List").exists()) {
        mutableList =
            ObjectInputStream(FileInputStream("$cacheDataPath/${file.name}-List")).readObject() as MutableList<Compare>
    } else {
        findDuplicates(mutableList, file)//寻找相似的
        ObjectOutputStream(FileOutputStream(File("$cacheDataPath/${file.name}-List").createParentFolder())).writeObject(
            mutableList
        )
    }

    val workbook = XSSFWorkbook()
    val sheet = workbook.createSheet()
    val firstRow = sheet.createRow(0)
    for ((i, str) in listOf("文件名", "是否有相似", "最高疑似度", "最高相似文件链接", "其他链接").withIndex()) {
        firstRow.createCell(i).centerCell().apply {
            setCellValue(str)
        }
    }
    sheet.setColumnWidth(0, 30 * 256)
    sheet.setColumnWidth(1, 15 * 256)
    sheet.setColumnWidth(2, 15 * 256)
    sheet.setColumnWidth(3, 200 * 256)
    sheet.setColumnWidth(4, 200 * 256)
    val sortedByDescending = mutableList.sortedByDescending { compare ->
        if (compare.isRepeat && compare.repeatList.isNotEmpty()) {
            compare.repeatList.sortedByDescending { it.similarityRate }[0].similarityRate
        } else 0.0
    }
    for ((i, compare) in sortedByDescending.withIndex()) {
        val row = sheet.createRow(i + 1)
        row.createCell(0).centerCell().setCellValue(compare.fileName)
        row.createCell(1).centerCell()
            .setCellValue(if (compare.isRepeat && compare.repeatList.isNotEmpty()) "是" else "否")
        row.height = (450).toShort()
        if (compare.isRepeat && compare.repeatList.isNotEmpty()) {
            val maxRepeat = compare.repeatList.sortedByDescending { it.similarityRate }[0]
            val otherRepeats = compare.repeatList.filterNot { it == maxRepeat }
            row.createCell(2).centerCell().setCellValue(maxRepeat.similarityRate)
            row.createCell(3).centerCell().apply {
                cellStyle = sheet.workbook.createCellStyle().apply {
                    setFont(sheet.workbook.createFont().apply {
                        underline = 1
                        color = IndexedColors.BLUE.index
                    })
                    alignment = HorizontalAlignment.CENTER
                    verticalAlignment = VerticalAlignment.CENTER
                }
            }.cellFormula = "HYPERLINK(\"${maxRepeat.url}\")"
            var enterCnt = 0
            row.createCell(4).centerCell().apply {
                cellStyle.alignment = HorizontalAlignment.LEFT
            }.setCellValue(XSSFRichTextString(StringBuilder().apply {
                otherRepeats.forEach {
                    append("${it.similarityRate}  ${it.url}")
                    if (otherRepeats.indexOf(it) != otherRepeats.lastIndex) append("\r\n")
                    enterCnt++
                }
            }.toString()))
            row.height = ((if (enterCnt == 0) 1 else enterCnt) * 400).toShort()
        }
    }
    workbook.write(File("$excelOutputPath/${file.name}.xlsx").createParentFolder().outputStream())
    println()
}

/**
 * 让一个Cell居中
 */
fun XSSFCell.centerCell(): XSSFCell {
    cellStyle = sheet.workbook.createCellStyle().apply {
        alignment = HorizontalAlignment.CENTER
        verticalAlignment = VerticalAlignment.CENTER
        wrapText = true
    }
    return this
}


/**
 * 主要方法，该方法会对传入的文件夹中的java和kt文件和github上进行比对
 * @param assessmentList 会把每个文件数据存到这个list当中
 * @param repositories 需要查询的文件夹
 */
fun findDuplicates(
    assessmentList: MutableList<Compare>,
    repositories: File
) {
    val mutableList = mutableListOf<File>()
    val reName = if (repositories.isDirectory) repositories.name.substringBefore("-") else {
        Exception("传入必须为仓库文件夹根目录").printStackTrace()
        return
    }
    findKtOrJavaFile(mutableList, repositories)
    for (file in mutableList) {
        val fieldString = file.readText()//文件内容
        val className = file.name//文件名
        var data = ""//由于github的搜索机制，所以只能搜一行，多行不准确
        val compile = Pattern.compile("class .+").matcher(fieldString)
        if (compile.find()) {
            data = compile.group()
        } else {
            val compile1 = Pattern.compile("interface .+").matcher(fieldString)
            if (compile1.find()) {
                data = compile1.group()
            } else {
                val compile2 = Pattern.compile("object .+").matcher(fieldString)
                if (compile2.find()) {
                    data = compile2.group()
                }
            }
        }
        if (data.isBlank()) continue//如果没有匹配到类或者接口则跳过
        val jsoup = Jsoup.connect("https://github.com/search")
            .addHeaders()
            .addCookies()
            .data("type", "Code")
            .data("l", "Kotlin")
            .data("q", data)
        var get: Document? = null
        Observable.create<Document> {
            it.onNext(jsoup.get())
        }
            .doOnError {
                println("出错：等待重试")
                Thread.sleep(1000 * 60)
            }
            .retry(5)
            .subscribe({
                get = it
            }, {})
        get ?: continue
        val elementsByClass =
            get!!.getElementsByClass("hx_hit-code code-list-item d-flex py-4 code-list-item-public ")
        val list = elementsByClass
            .run {
                subList(0, if (this.size > 8) 8 else size)
            }
            .filter {
                val text = it.getElementsByClass("f4 text-normal").text()
                val matcher = Pattern.compile("[^/]+\\.kt").matcher(text.replace(Regex("\\s"), ""))
                if (text.isNotBlank() && matcher.find()) {
                    //挑出类名相同的
                    val substringAfter =
                        it.getElementsByClass("flex-shrink-0 text-small text-bold").text().substringAfter("/")
                    matcher.group() == className
                            && substringAfter != reName
                } else
                    false
            }.map {
                val elements = it
                    .getElementsByClass("f4 text-normal")
                    .select("a")
                    .attr("href")
                "https://github.com${elements}"
            }
        if (list.isEmpty()) {
            assessmentList.add(Compare(className, false, listOf()))
            println("文件：${file.absolutePath}:暂未查到有重复")
        } else {
            println("文件：${file.absolutePath}")
        }
        val map = list.map { url: String ->
            var get1: Document? = null
            Observable.create<Document> {
                it.onNext(
                    Jsoup.connect(url)
                        .addCookies()
                        .addHeaders().get()
                )
            }
                .doOnError {
                    println("出错：等待重试")
                    Thread.sleep(1000 * 60)
                }
                .retry(5)
                .subscribe({
                    get1 = it
                }, {})
            val elements = get1!!.getElementsByClass("highlight tab-size js-file-line-container")
            println("链接：$url")
            val text = elements.text()
            val data1 = className.substringBeforeLast(".")
            val internationStr = text.clean(data1)
            val locationStr = fieldString.clean(data1)
            val similarity = CosineSimilarity.getSimilarity(internationStr, locationStr)
            println("疑似度：$similarity")
            Repeat(similarity, text, fieldString, url)
        }
        assessmentList.add(Compare(className, true, map))
    }
}

/**
 * 对每个文件中都会出现的一些东西进行过滤
 */
fun String.clean(vararg data: String): String {
    fun String.delete(str: String): String {
        return replace(str, "")
    }

    fun String.deleteRegex(regex: Regex): String {
        return replace(regex, "")
    }

    return kotlin.run {
        var str = this
        data.forEach {
            str = str.delete(it)
        }
        str
    }
        .deleteRegex(Regex("import [\\w|.]+"))//清除导包
        .deleteRegex(Regex("package [\\w|.]+"))
        .deleteRegex(Regex("//.+\\s"))//清除注释
        .deleteRegex(Regex("/\\*[.|\\s]+\\*/"))//清除注释
        .deleteRegex(Regex("\\s"))//这行一定要放在最后
}


/**
 * github 爬虫添加Cookies专用，如果失效请马上更新Cookie
 */
fun Connection.addCookies(): Connection {
    cookie("_octo", "GH1.1.854670984.1594816463")
    cookie("_ga", "GA1.2.607223545.1594816464")
    cookie("tz", "Asia%2FShanghai")
    cookie("_device_id", "1a083986458838ef628823c31b347940")
    cookie("has_recent_activity", "1")
    cookie("_gat", "1")
    cookie("user_session", "eb_dQ_EuM5dGZfSZUFAtq9cDmDUJ1-0pHOxgKM0K96MXnUG3")
    cookie("__Host-user_session_same_site", "eb_dQ_EuM5dGZfSZUFAtq9cDmDUJ1-0pHOxgKM0K96MXnUG3")
    cookie("logged_in", "yes")
    cookie("dotcom_user", "Jovines")
    cookie(
        "_gh_sess",
        "zv4w4ZbtkN%2BzuFiemC8WYU3NB%2BYlyEcWkHChFIN8%2BIO9vQB%2Bp6fJ0B6Npikd3JZ31MjkG%2FyiUc3ykRyJVbTOH1gZ%2F6v%2FB5K9I63OtuRANFf3t4uPBA%2BhPNqarrXsP%2FlKzfpeYjuZi%2B59guM9NxtoZtw8EvrY%2Bxl73nwItgozk%2BOdCizlBetPU1345K7RSM0QjdWcmUYvnsx13yHqwQopKgowdlQ5OuHp%2Ble8LJidaLHaJhlDlQA7EXyWvX8YUKOG%2BwqMkIvJICRHOTXDGf0RZozRNsG%2BXQkX7hkIINeQuINDIDEr%2FzLcb4aYixHO8ox0ZubNTAe1VcO3yNWBuiPwdnpci4AkyOIyMX63EaMKloDX248wKtLlUkXjvLjMhZdZEs7j1oX5WkEJnjvuO3PdrsuHC8bKDqqnMIsSYnZdVsDzL2aah2nWKJHj8GTcPJ8cHsXSQ06e8SPpkOR8pCwlSACZcWxNgw36NGonhn5yl8RY5Z3C8E%2B3phqXpOhx%2FDGP3VvoBTWiutnpiAmCf928nPMzFHJnWC3taXbNC%2FL1o2PzpxqFmQCx18k5QC%2Bt1cxLZHAYz7L67lbIO19aI6UdwjWIIQUzLjVNZf8sMiVJjDZI2DykagCFczJpUsmzvUpm%2F7GK9rGAhoGDXdNvj8S1d8avvAKtnLwTUuoG0uvbaNqShkehOysuXuMIFd6X9QB1GhQRae7f6LL6dGb%2Fzu192XgHRf59IY9x5nT7w2qOcZNhAKJRdRUOdM5DUSYzGKE82R41ST13PhhI4FNn6MgHlQ%2BazoYBjAYZxbGa4cljN0qUX8wosHpn1qSY5GnOOV%2F%2B2SbZoW93HtU%3D--JAiX%2BHsEA7V1hLuv--Up9qX0cEhssalYBNOqid9Q%3D%3D"
    )
    return this
}

/**
 * github 添加header专用
 */
fun Connection.addHeaders(): Connection {
    header("Connection", "keep-alive")
    header("Cache-Control", "max-age=0")
    header("Upgrade-Insecure-Requests", "1")
    header(
        "User-Agent",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/84.0.4147.89 Safari/537.36"
    )
    header(
        "Accept",
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9"
    )
    header("Sec-Fetch-Site", "same-origin")
    header("Sec-Fetch-Mode", "navigate")
    header("Sec-Fetch-User", "?1")
    header("Sec-Fetch-Dest", "document")
    header("Accept-Language", "zh-CN,zh;q=0.9")
    timeout(1000 * 1000)
    return this
}



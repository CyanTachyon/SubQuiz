package moe.tachyon.quiz.utils

import io.github.bonigarcia.wdm.WebDriverManager
import io.ktor.util.*
import moe.tachyon.quiz.logger.SubQuizLogger
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.poi.sl.usermodel.PictureData
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.koin.core.component.KoinComponent
import org.openqa.selenium.*
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.support.ui.WebDriverWait
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import java.awt.Dimension
import java.io.ByteArrayOutputStream

object DocumentConversion: KoinComponent
{
    private val logger = SubQuizLogger.getLogger<DocumentConversion>()

    fun documentToImages(
        document: ByteArray,
        dpi: Int = 300
    ): List<BufferedImage>?
    {
        runCatching()
        {
            return listOf(ImageIO.read(document.inputStream())!!)
        }
        runCatching()
        {
            return pdfToImages(document, dpi)
        }
        return null // todo pptx/docx/xlsx to image
    }

    fun pdfToImages(
        pdf: ByteArray,
        dpi: Int = 300
    ): List<BufferedImage>
    {
        PDDocument.load(pdf).use()
        { document ->
            val images = mutableListOf<BufferedImage>()
            val renderer = PDFRenderer(document)
            for (pageIndex in 0 until document.numberOfPages)
            {
                val image = renderer.renderImageWithDPI(pageIndex, dpi.toFloat())
                if (image != null) images.add(image)
            }
            return images
        }
    }


    fun renderHtmlToImage(
        html: String,
        timeoutSec: Int,
        elementSelector: String = "body",
        waitForResources: Boolean = true
    ): BufferedImage
    {
        WebDriverManager.chromedriver().setup()

        val options = ChromeOptions()
        options.addArguments("--headless=new")
        options.addArguments("--disable-gpu")
        options.addArguments("--no-sandbox")
        options.addArguments("--window-size=4800,3200")
        options.addArguments("--enable-font-antialiasing")
        options.addArguments("--disable-software-rasterizer")
        options.addArguments("--disable-dev-shm-usage")
        options.addArguments("--disable-background-timer-throttling")
        options.addArguments("--disable-renderer-backgrounding")
        options.addArguments("--high-dpi-support=1")
        options.addArguments("--window-size=3840,2160")  // 使用常规分辨率

        val driver = ChromeDriver(options)
        try
        {
            val dataUrl = "data:text/html;base64,${html.encodeBase64()}"

            driver.get(dataUrl)

            val wait = WebDriverWait(driver, timeoutSec.seconds.toJavaDuration())

            // 等待 document.readyState === 'complete'
            wait.until<Boolean?>
            { d: WebDriver? ->
                (d as JavascriptExecutor).executeScript("return document.readyState") == "complete"
            }

            wait.until<Boolean?>
            { d: WebDriver? ->
                val o = (d as JavascriptExecutor).executeScript(
                    "if(!document.body) return false; " +
                    "var r = document.body.getBoundingClientRect(); " +
                    "return (r.width>0 && r.height>0);"
                )
                o == true
            }

            if (waitForResources) wait.until<Boolean?>
            { d: WebDriver? ->
                val js = d as JavascriptExecutor
                val result = js.executeScript("""
                    function checkAllResources() 
                    {
                        const images = document.querySelectorAll('img');
                        for (let img of images)
                            if (!img.complete || img.naturalHeight === 0) 
                                return false;
                        if (document.fonts && document.fonts.status !== 'loaded')
                            return false;
                        if (window.performance) 
                        {
                            const resources = performance.getEntriesByType('resource');
                            for (let resource of resources) 
                                if (resource.initiatorType === 'img' || resource.initiatorType === 'css')
                                    if (!resource.responseEnd)
                                        return false;
                        }
                        return true;
                    }
                    return checkAllResources();
                """.trimIndent())
                result == true
            }

            // 读取最终 body bounding rect 和 devicePixelRatio
            @Suppress("UNCHECKED_CAST")
            val rect = (driver as JavascriptExecutor).executeScript(
                "var r = document.querySelector('${elementSelector}').getBoundingClientRect(); " +
                "return {x: r.left, y: r.top, width: r.width, height: r.height, dpr: window.devicePixelRatio || 1};"
            ) as MutableMap<String?, Any?>

            val x = (rect["x"] as Number).toDouble()
            val y = (rect["y"] as Number).toDouble()
            val width = (rect["width"] as Number).toDouble()
            val height = (rect["height"] as Number).toDouble()
            val dpr = (rect["dpr"] as Number).toDouble()
            val clip = HashMap<String?, Any?>()
            val clipX = x * dpr
            val clipY = y * dpr
            val clipW = max(1.0, width * dpr)
            val clipH = max(1.0, height * dpr)
            clip["x"] = clipX
            clip["y"] = clipY
            clip["width"] = clipW
            clip["height"] = clipH
            clip["scale"] = dpr
            val params = HashMap<String?, Any?>()
            params["format"] = "png"
            params["clip"] = clip
            val result = driver.executeCdpCommand("Page.captureScreenshot", params) as MutableMap<String?, Any?>
            val imageBase64 = result["data"] as String
            val imageBytes: ByteArray = imageBase64.decodeBase64Bytes()
            val img = ImageIO.read(ByteArrayInputStream(imageBytes))
            return img
        }
        finally
        {
            try
            {
                driver.quit()
            }
            catch (_: Exception)
            {
            }
        }
    }

    fun imagesToPptx(images: List<BufferedImage>): ByteArray
    {
        require(images.isNotEmpty()) { "images must not be null or empty" }
        var maxW = 0
        var maxH = 0
        for (img in images)
        {
            maxW = max(maxW, img.width)
            maxH = max(maxH, img.height)
        }
        XMLSlideShow().use()
        { ppt ->
            ppt.setPageSize(Dimension(maxW, maxH))
            for (img in images)
            {
                val pngBytes = img.toJpegBytes()
                val idx = ppt.addPicture(pngBytes, PictureData.PictureType.JPEG)
                val slide = ppt.createSlide()
                val pic = slide.createPicture(idx)
                val imgW = img.width
                val imgH = img.height
                val x = (maxW - imgW) / 2.0
                val y = (maxH - imgH) / 2.0
                pic.setAnchor(Rectangle2D.Double(x, y, imgW.toDouble(), imgH.toDouble()))
            }
            ByteArrayOutputStream().use()
            { out ->
                ppt.write(out)
                return out.toByteArray()
            }
        }
    }
}
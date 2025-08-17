package com.paulcity.nocturnecompanion.utils

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class ExtractedColor(
    val color: Int,
    val hex: String,
    val count: Int = 0
)

data class GradientInfo(
    val colors: List<ExtractedColor>,
    val gradientColors: List<Int>,
    val linearGradient: List<Int> = gradientColors,
    val radialGradient: List<Int> = gradientColors,
    val sweepGradient: List<Int> = gradientColors,
    val vibrantGradient: List<Int> = gradientColors
)

object GradientUtils {
    
    /**
     * Extract dominant colors from a bitmap, similar to nocturne-ui's colorExtractor.js
     */
    fun extractColorsFromBitmap(bitmap: Bitmap, numColors: Int = 5, quality: Int = 10): List<ExtractedColor> {
        val width = bitmap.width
        val height = bitmap.height
        val colorMap = mutableMapOf<Int, Int>()
        
        // Sample pixels from a grid
        for (y in 0 until height step quality) {
            for (x in 0 until width step quality) {
                val pixel = bitmap.getPixel(x, y)
                
                // Extract RGB values
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                
                // Skip colors that are too close to pure black/white (primitive filter)
                if (r > 10 && r < 245 && g > 10 && g < 245 && b > 10 && b < 245) {
                    val quantizedColor = quantizeColor(r, g, b)
                    colorMap[quantizedColor] = colorMap.getOrDefault(quantizedColor, 0) + 1
                }
            }
        }
        
        // If we didn't get enough colors, do a less picky pass
        if (colorMap.size < numColors) {
            for (y in 0 until height step quality) {
                for (x in 0 until width step quality) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    
                    val quantizedColor = quantizeColor(r, g, b)
                    colorMap[quantizedColor] = colorMap.getOrDefault(quantizedColor, 0) + 1
                }
            }
        }
        
        // Convert to list and sort by count, then by brightness
        return colorMap.entries
            .map { (color, count) ->
                ExtractedColor(
                    color = color,
                    hex = colorToHex(color),
                    count = count
                )
            }
            .sortedWith(compareByDescending<ExtractedColor> { it.count }
                .thenByDescending { getBrightness(it.color) })
            .take(numColors)
    }
    
    /**
     * Generate a complete gradient info object with different gradient variations
     */
    fun generateGradientFromBitmap(bitmap: Bitmap, numColors: Int = 5): GradientInfo {
        val extractedColors = extractColorsFromBitmap(bitmap, numColors)
        val baseColors = extractedColors.map { it.color }
        
        // Linear gradient (original order)
        val linearGradient = baseColors
        
        // Radial gradient (brightest to darkest from center)
        val radialGradient = baseColors.sortedByDescending { getBrightness(it) }
        
        // Sweep gradient (color wheel arrangement by hue)
        val sweepGradient = baseColors.sortedBy { getHue(it) }
        
        // Vibrant gradient (most vibrant colors first)
        val vibrantGradient = baseColors.sortedByDescending { getVibrance(it) }
        
        return GradientInfo(
            colors = extractedColors,
            gradientColors = baseColors,
            linearGradient = linearGradient,
            radialGradient = radialGradient,
            sweepGradient = sweepGradient,
            vibrantGradient = vibrantGradient
        )
    }
    
    /**
     * Quantize color to reduce similar colors (reduce to 16 levels per channel)
     */
    private fun quantizeColor(r: Int, g: Int, b: Int, levels: Int = 16): Int {
        val factor = 256 / levels
        val qR = (r / factor) * factor
        val qG = (g / factor) * factor
        val qB = (b / factor) * factor
        return Color.rgb(qR, qG, qB)
    }
    
    /**
     * Convert color int to hex string
     */
    private fun colorToHex(color: Int): String {
        return String.format("#%06X", 0xFFFFFF and color)
    }
    
    /**
     * Calculate brightness of a color
     */
    private fun getBrightness(color: Int): Float {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        // Using luminance formula
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    }
    
    /**
     * Calculate color vibrance (saturation)
     */
    fun getVibrance(color: Int): Float {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        
        val maxVal = max(max(r, g), b)
        val minVal = min(min(r, g), b)
        
        return if (maxVal == 0f) 0f else (maxVal - minVal) / maxVal
    }
    
    /**
     * Calculate color hue for color wheel arrangement
     */
    private fun getHue(color: Int): Float {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        
        val maxVal = max(max(r, g), b)
        val minVal = min(min(r, g), b)
        val delta = maxVal - minVal
        
        if (delta == 0f) return 0f
        
        val hue = when (maxVal) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            b -> 60f * (((r - g) / delta) + 4f)
            else -> 0f
        }
        
        return if (hue < 0f) hue + 360f else hue
    }
    
    /**
     * Create a smooth gradient by interpolating between colors
     */
    fun createSmoothGradient(colors: List<Int>, steps: Int = 10): List<Int> {
        if (colors.isEmpty()) return emptyList()
        if (colors.size == 1) return listOf(colors[0])
        
        val gradientColors = mutableListOf<Int>()
        
        for (i in 0 until colors.size - 1) {
            val startColor = colors[i]
            val endColor = colors[i + 1]
            
            for (step in 0 until steps) {
                val ratio = step.toFloat() / steps
                val interpolatedColor = interpolateColor(startColor, endColor, ratio)
                gradientColors.add(interpolatedColor)
            }
        }
        
        gradientColors.add(colors.last())
        return gradientColors
    }
    
    /**
     * Interpolate between two colors
     */
    private fun interpolateColor(color1: Int, color2: Int, ratio: Float): Int {
        val invRatio = 1f - ratio
        
        val r = (Color.red(color1) * invRatio + Color.red(color2) * ratio).toInt()
        val g = (Color.green(color1) * invRatio + Color.green(color2) * ratio).toInt()
        val b = (Color.blue(color1) * invRatio + Color.blue(color2) * ratio).toInt()
        
        return Color.rgb(r, g, b)
    }
    
    /**
     * Create a more natural, organic gradient with intermediate blended colors
     */
    fun createOrganicGradient(colors: List<Int>, intermediateSteps: Int = 3): List<Int> {
        if (colors.isEmpty()) return emptyList()
        if (colors.size == 1) return listOf(colors[0])
        
        val organicColors = mutableListOf<Int>()
        
        for (i in 0 until colors.size - 1) {
            val startColor = colors[i]
            val endColor = colors[i + 1]
            
            // Add the start color
            organicColors.add(startColor)
            
            // Create intermediate colors with slight variations
            for (step in 1..intermediateSteps) {
                val ratio = step.toFloat() / (intermediateSteps + 1)
                val blendedColor = blendColors(startColor, endColor, ratio)
                organicColors.add(blendedColor)
            }
        }
        
        // Add the final color
        organicColors.add(colors.last())
        return organicColors
    }
    
    /**
     * Blend two colors with slight random variations for more natural transitions
     */
    private fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
        val invRatio = 1f - ratio
        
        // Add slight variations to make it less mechanical
        val variation = 0.05f // 5% variation
        val adjustedRatio = (ratio + (Math.random().toFloat() - 0.5f) * variation).coerceIn(0f, 1f)
        val adjustedInvRatio = 1f - adjustedRatio
        
        val r = (Color.red(color1) * adjustedInvRatio + Color.red(color2) * adjustedRatio).toInt().coerceIn(0, 255)
        val g = (Color.green(color1) * adjustedInvRatio + Color.green(color2) * adjustedRatio).toInt().coerceIn(0, 255)
        val b = (Color.blue(color1) * adjustedInvRatio + Color.blue(color2) * adjustedRatio).toInt().coerceIn(0, 255)
        
        return Color.rgb(r, g, b)
    }
    
    /**
     * Create subtle color variations by adjusting brightness and saturation
     */
    fun createSubtleVariations(color: Int, count: Int = 5): List<Int> {
        val variations = mutableListOf<Int>()
        
        for (i in 0 until count) {
            val factor = 0.7f + (i * 0.1f) // Range from 0.7 to 1.1
            val adjustedColor = adjustColorBrightness(color, factor)
            variations.add(adjustedColor)
        }
        
        return variations
    }
    
    /**
     * Adjust color brightness while maintaining hue
     */
    private fun adjustColorBrightness(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        
        return Color.rgb(r, g, b)
    }
    
    /**
     * Create alpha variations of colors for layered effects
     */
    fun createAlphaVariations(colors: List<Int>): List<androidx.compose.ui.graphics.Color> {
        return colors.mapIndexed { index, color ->
            val alpha = when (index % 3) {
                0 -> 0.8f
                1 -> 0.6f
                else -> 0.9f
            }
            androidx.compose.ui.graphics.Color(color).copy(alpha = alpha)
        }
    }
}
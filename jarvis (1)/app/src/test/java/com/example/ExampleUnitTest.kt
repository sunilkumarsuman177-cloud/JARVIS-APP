package com.example

import com.example.engine.LlamaBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun offlineIntentCompiler_generatesValidJsonForAllSchemas() {
        assertTrue(LlamaBridge.compileOfflineIntentJson("turn on wifi").contains("\"action\":\"wifi\""))
        assertTrue(LlamaBridge.compileOfflineIntentJson("turn off bluetooth").contains("\"action\":\"bluetooth\""))
        assertTrue(LlamaBridge.compileOfflineIntentJson("turn on flashlight").contains("\"action\":\"flashlight\""))
        assertTrue(LlamaBridge.compileOfflineIntentJson("enable do not disturb").contains("\"action\":\"dnd\""))
        assertTrue(LlamaBridge.compileOfflineIntentJson("set volume to 75").contains("\"level\":75"))
        assertTrue(LlamaBridge.compileOfflineIntentJson("open whatsapp").contains("\"query\":\"whatsapp\""))
        assertTrue(LlamaBridge.compileOfflineIntentJson("call Mom").contains("\"name\":\"Mom\""))
        assertTrue(LlamaBridge.compileOfflineIntentJson("set alarm for 30 minutes").contains("\"minutes\":30"))
        assertTrue(LlamaBridge.compileOfflineIntentJson("click Send").contains("\"text\":\"Send\""))
        assertTrue(LlamaBridge.compileOfflineIntentJson("scroll down").contains("\"target\":\"scroll_down\""))
        assertTrue(LlamaBridge.compileOfflineIntentJson("who made you").contains("Prince Priyadarshi"))
    }

    @Test
    fun fuzzyWakeWord_matchesHisJarvisAndNeverTreatsWakeAsFailedCommand() {
        assertTrue(LlamaBridge.containsWakeWordFuzzy("his jarvis", "hey jarvis"))
        assertTrue(LlamaBridge.containsWakeWordFuzzy("he jarvis", "hey jarvis"))
        assertTrue(LlamaBridge.containsWakeWordFuzzy("hi jarvis", "hey jarvis"))
        assertEquals("", LlamaBridge.stripWakeWordAndExtractCommand("his jarvis", "hey jarvis"))
        assertEquals("open whatsapp", LlamaBridge.stripWakeWordAndExtractCommand("his jarvis open whatsapp", "hey jarvis"))
        assertTrue(LlamaBridge.compileOfflineIntentJson("his jarvis").contains("\"action\":\"noop\""))
    }

    @Test
    fun conversationalBilingualNlu_understandsNaturalSentencesAndMultiStepTasks() {
        // Natural conversational sentences
        assertTrue(
            LlamaBridge.compileOfflineIntentJson("I wanna see my messages so open whatsapp")
                .contains("\"query\":\"whatsapp\"")
        )
        assertTrue(
            LlamaBridge.compileOfflineIntentJson("mera whatsapp kholo")
                .contains("\"query\":\"whatsapp\"")
        )
        assertTrue(
            LlamaBridge.compileOfflineIntentJson("light jalao")
                .contains("\"action\":\"flashlight\"")
        )

        // Multi-step YouTube play
        val yt1 = LlamaBridge.compileOfflineIntentJson("Open YouTube, search for Shiv Tandav and play the first result")
        assertTrue(yt1.contains("\"action\":\"youtube_play\""))
        assertTrue(yt1.lowercase().contains("shiv tandav"))

        val yt2 = LlamaBridge.compileOfflineIntentJson("mujhe shiv tandav sunna hai")
        assertTrue(yt2.contains("\"action\":\"youtube_play\""))
        assertTrue(yt2.lowercase().contains("shiv tandav"))

        // Multi-step WhatsApp message
        val wa1 = LlamaBridge.compileOfflineIntentJson("Open WhatsApp and message Rahul hello bro")
        assertTrue(wa1.contains("\"action\":\"whatsapp_send\""))
        assertTrue(wa1.contains("\"contact\":\"Rahul\""))
        assertTrue(wa1.contains("\"message\":\"hello bro\""))

        val wa2 = LlamaBridge.compileOfflineIntentJson("message Papa hi on whatsapp")
        assertTrue(wa2.contains("\"action\":\"whatsapp_send\""))
        assertTrue(wa2.contains("\"contact\":\"Papa\""))
        assertTrue(wa2.contains("\"message\":\"hi\""))

        // Unknown command -> ONLY "Not understood", never repeats user words or lists instructions
        val unknown = LlamaBridge.compileOfflineIntentJson("quantum hyperdrive flux capacitor")
        assertEquals("""{"action":"speak","text":"Not understood"}""", unknown)
        assertFalse(unknown.contains("Heard:"))
        assertFalse(unknown.contains("Say open"))
    }
}

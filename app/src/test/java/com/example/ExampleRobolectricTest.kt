package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleAppTest {

  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testScheduleKeyValues() {
    val days = listOf("SENIN", "SELASA", "RABU", "KAMIS", "JUMAT")
    assertEquals(5, days.size)
    assertTrue(days.contains("SENIN"))
  }
}

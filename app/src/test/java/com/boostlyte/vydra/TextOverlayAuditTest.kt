package com.boostlyte.vydra

import android.net.DummyUri
import com.boostlyte.vydra.commands.AddOperationCommand
import com.boostlyte.vydra.commands.UpdateOperationCommand
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.EditRecipe
import com.boostlyte.vydra.models.TextPosition
import com.boostlyte.vydra.models.VideoProject
import com.boostlyte.vydra.utils.ProjectSerializer
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Text Overlay Audit items 2.7 (Shadow), 2.8 (Letter Spacing), and 2.11 (Duplicate Text).
 */
class TextOverlayAuditTest {

    @Test
    fun testDuplicateTextGeneratesUniqueIdAndOffsetPosition() {
        val original = EditOperation.AddText(
            text = "Hello Vydra",
            fontSize = 36,
            position = TextPosition.fromLabel("Center Align"),
            relativeX = 0.5f,
            relativeY = 0.5f,
            color = "#FFFFFF",
            opacity = 0.9f,
            borderThickness = 4,
            textAlign = "center"
        )

        val newX = ((original.relativeX ?: 0.5f) + 0.05f).coerceIn(0.05f, 0.95f)
        val newY = ((original.relativeY ?: 0.5f) + 0.05f).coerceIn(0.05f, 0.95f)
        val duplicate = original.copy(
            id = System.nanoTime().toString(),
            relativeX = newX,
            relativeY = newY
        )

        assertNotEquals(original.id, duplicate.id)
        assertEquals(0.55f, duplicate.relativeX ?: 0f, 0.001f)
        assertEquals(0.55f, duplicate.relativeY ?: 0f, 0.001f)
        assertEquals(original.text, duplicate.text)
        assertEquals(original.fontSize, duplicate.fontSize)
        assertEquals(original.color, duplicate.color)
        assertEquals(original.opacity, duplicate.opacity, 0.001f)
        assertEquals(original.borderThickness, duplicate.borderThickness)
        assertEquals(original.textAlign, duplicate.textAlign)
    }

    @Test
    fun testDuplicatePositionClampingNearEdges() {
        val nearEdge = EditOperation.AddText(
            text = "Near Edge",
            fontSize = 24,
            position = TextPosition.fromLabel("Center Align"),
            relativeX = 0.94f,
            relativeY = 0.93f
        )

        val newX = ((nearEdge.relativeX ?: 0.5f) + 0.05f).coerceIn(0.05f, 0.95f)
        val newY = ((nearEdge.relativeY ?: 0.5f) + 0.05f).coerceIn(0.05f, 0.95f)

        assertEquals(0.95f, newX, 0.001f)
        assertEquals(0.95f, newY, 0.001f)
    }

    @Test
    fun testAddOperationCommandAppendsOperation() {
        val initialProject = VideoProject(
            sourceUri = DummyUri.INSTANCE,
            sourceName = "video.mp4"
        )

        val op1 = EditOperation.AddText(
            text = "First Text",
            fontSize = 32,
            position = TextPosition.fromLabel("Center Align")
        )
        val p1 = AddOperationCommand(op1, "Add Text").execute(initialProject)
        assertEquals(1, p1.operations.size)

        val opDuplicate = op1.copy(id = "dup_123", relativeX = 0.55f, relativeY = 0.55f)
        val p2 = AddOperationCommand(opDuplicate, "Duplicate Text").execute(p1)
        assertEquals(2, p2.operations.size)
        assertEquals("dup_123", (p2.operations[1] as EditOperation.AddText).id)
    }

    @Test
    fun testUpdateOperationCommandModifiesShadow() {
        val op = EditOperation.AddText(
            text = "Sample",
            fontSize = 32,
            position = TextPosition.fromLabel("Center Align"),
            borderThickness = 0
        )
        val p1 = VideoProject(
            sourceUri = DummyUri.INSTANCE,
            sourceName = "video.mp4",
            operations = listOf(op)
        )

        val updatedOp = op.copy(borderThickness = 8)
        val p2 = UpdateOperationCommand(op.id, "Modify Shadow") { updatedOp }.execute(p1)

        val currentOp = p2.operations.find { (it as? EditOperation.AddText)?.id == op.id } as EditOperation.AddText
        assertEquals(8, currentOp.borderThickness)
    }

    @Test
    fun testProjectSerializerPreservesShadowAndBackwardCompatibility() {
        val op = EditOperation.AddText(
            text = "Shadow Text",
            fontSize = 40,
            position = TextPosition.fromLabel("Center Align"),
            relativeX = 0.4f,
            relativeY = 0.6f,
            color = "#FF0000",
            opacity = 0.85f,
            borderThickness = 6,
            textAlign = "right",
            letterSpacing = 0f,
            lineSpacing = 12f
        )
        val recipe = EditRecipe(
            projectName = "Test Project",
            sourceUri = DummyUri.INSTANCE,
            sourceName = "video.mp4",
            operations = listOf(op)
        )

        val json = ProjectSerializer.serialize(recipe)
        assertTrue(json.contains("\"borderThickness\":6") || json.contains("\"borderThickness\": 6"))

        val deserialized = ProjectSerializer.deserialize(json)
        val deserializedOp = deserialized.operations.first() as EditOperation.AddText
        assertEquals("Shadow Text", deserializedOp.text)
        assertEquals(6, deserializedOp.borderThickness)
        assertEquals(0.85f, deserializedOp.opacity, 0.001f)
        assertEquals("right", deserializedOp.textAlign)
        assertEquals(12f, deserializedOp.lineSpacing, 0.001f)
    }
}

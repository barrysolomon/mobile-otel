package com.mobile.observability.demo.workflow

import android.util.Log

class WorkflowEvaluator(private val config: DSLConfig) {

    fun evaluate(event: Event): List<TriggerResult> {
        val results = mutableListOf<TriggerResult>()

        for (workflow in config.workflows) {
            if (!workflow.enabled) continue

            if (evaluateTrigger(workflow.trigger, event)) {
                Log.d(TAG, "Workflow ${workflow.id} triggered by event ${event.eventName}")
                results.add(
                    TriggerResult(
                        triggered = true,
                        workflowId = workflow.id,
                        actions = workflow.actions
                    )
                )
            }
        }

        return results
    }

    private fun evaluateTrigger(trigger: Trigger, event: Event): Boolean {
        return when {
            trigger.any != null -> evaluateAny(trigger.any, event)
            trigger.all != null -> evaluateAll(trigger.all, event)
            else -> false
        }
    }

    private fun evaluateAny(conditions: List<TriggerCondition>, event: Event): Boolean {
        return conditions.any { evaluateCondition(it, event) }
    }

    private fun evaluateAll(conditions: List<TriggerCondition>, event: Event): Boolean {
        return conditions.all { evaluateCondition(it, event) }
    }

    private fun evaluateCondition(condition: TriggerCondition, event: Event): Boolean {
        // Check event name match
        if (condition.event != null && condition.event != event.eventName) {
            return false
        }

        // Check predicates
        if (condition.where != null) {
            return condition.where.all { evaluatePredicate(it, event.attributes) }
        }

        // If only event name specified and no predicates, it's a match
        return condition.event == event.eventName
    }

    private fun evaluatePredicate(predicate: Predicate, attributes: Map<String, Any>): Boolean {
        val attrValue = attributes[predicate.attr] ?: return false

        return when (predicate.op) {
            "==" -> compareEquals(attrValue, predicate.value)
            "!=" -> !compareEquals(attrValue, predicate.value)
            ">" -> compareGreater(attrValue, predicate.value)
            ">=" -> compareGreaterOrEqual(attrValue, predicate.value)
            "<" -> compareLess(attrValue, predicate.value)
            "<=" -> compareLessOrEqual(attrValue, predicate.value)
            "contains" -> compareContains(attrValue, predicate.value)
            "regex" -> compareRegex(attrValue, predicate.value)
            else -> {
                Log.w(TAG, "Unknown operator: ${predicate.op}")
                false
            }
        }
    }

    private fun compareEquals(a: Any, b: Any): Boolean {
        return when {
            a is Number && b is Number -> a.toDouble() == b.toDouble()
            else -> a.toString() == b.toString()
        }
    }

    private fun compareGreater(a: Any, b: Any): Boolean {
        return try {
            val aNum = toNumber(a)
            val bNum = toNumber(b)
            aNum > bNum
        } catch (e: Exception) {
            false
        }
    }

    private fun compareGreaterOrEqual(a: Any, b: Any): Boolean {
        return try {
            val aNum = toNumber(a)
            val bNum = toNumber(b)
            aNum >= bNum
        } catch (e: Exception) {
            false
        }
    }

    private fun compareLess(a: Any, b: Any): Boolean {
        return try {
            val aNum = toNumber(a)
            val bNum = toNumber(b)
            aNum < bNum
        } catch (e: Exception) {
            false
        }
    }

    private fun compareLessOrEqual(a: Any, b: Any): Boolean {
        return try {
            val aNum = toNumber(a)
            val bNum = toNumber(b)
            aNum <= bNum
        } catch (e: Exception) {
            false
        }
    }

    private fun compareContains(a: Any, b: Any): Boolean {
        return a.toString().contains(b.toString(), ignoreCase = true)
    }

    private fun compareRegex(a: Any, b: Any): Boolean {
        return try {
            val regex = Regex(b.toString())
            regex.containsMatchIn(a.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Invalid regex: $b", e)
            false
        }
    }

    private fun toNumber(value: Any): Double {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: throw NumberFormatException()
            else -> throw NumberFormatException()
        }
    }

    companion object {
        private const val TAG = "WorkflowEvaluator"
    }
}

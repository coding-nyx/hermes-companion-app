package app.hermes.desk

import org.junit.rules.ExternalResource

/** Keep acceptance tests quiet even if the operator has enabled normal automatic voice entry. */
class DeskQuietEntryRule : ExternalResource() {
    override fun before() { DeskViewModel.suppressAutomaticEntryForTests = true }
    override fun after() { DeskViewModel.suppressAutomaticEntryForTests = false }
}

package xyz.thewhish.rustopsy.core

import xyz.thewhish.rustopsy.core.log.PrintTime
import xyz.thewhish.rustopsy.core.log.Snapshot

fun Snapshot.printedSec(): Double = when (val time = printedAt) {
    is PrintTime.Local -> time.time.toEpochSeconds() + 0.5
    is PrintTime.Utc -> time.epochMillis / 1_000.0
    null -> error("Snapshot has no print time")
}

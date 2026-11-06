package com.koodoreader.core.sync

object SyncCoordinator {
    fun export(target: SyncTarget, snapshot: ByteArray) = target.write(snapshot)

    fun import(target: SyncTarget): ByteArray? = target.read()?.use { it.readBytes() }
}

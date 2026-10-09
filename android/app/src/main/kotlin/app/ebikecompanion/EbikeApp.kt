package app.ebikecompanion

import android.app.Application
import app.ebikecompanion.ble.BridgeLink
import app.ebikecompanion.sync.SettingsStore
import app.ebikecompanion.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class EbikeApp : Application() {
    lateinit var link: BridgeLink
        private set
    lateinit var engine: SyncEngine
        private set

    override fun onCreate() {
        super.onCreate()
        link = BridgeLink(this)
        engine = SyncEngine(this, link, CoroutineScope(SupervisorJob() + Dispatchers.Default), SettingsStore(this))
        engine.start()
    }
}

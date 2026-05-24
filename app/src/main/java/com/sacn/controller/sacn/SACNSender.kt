package com.sacn.controller.sacn

// Re-export the shared sACN library types.
// The sACN Controller app uses these types directly; by re-exporting from
// the shared library we maintain backward-compatible import paths while
// deduplicating the protocol implementation.
typealias SACNSender = com.ecs.sacn.common.SACNSender
typealias SACNReceiver = com.ecs.sacn.common.SACNReceiver
typealias UniverseData = com.ecs.sacn.common.UniverseData
val dmx16 = com.ecs.sacn.common.dmx16
val dmx16Norm = com.ecs.sacn.common.dmx16Norm
val parseD16xy = com.ecs.sacn.common.parseD16xy

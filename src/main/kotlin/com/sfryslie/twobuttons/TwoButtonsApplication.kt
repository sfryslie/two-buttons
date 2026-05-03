package com.sfryslie.twobuttons

import com.sfryslie.twobuttons.config.ExperimentProperties
import com.sfryslie.twobuttons.config.ScoringProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(ExperimentProperties::class, ScoringProperties::class)
class TwoButtonsApplication

fun main(args: Array<String>) {
    runApplication<TwoButtonsApplication>(*args)
}

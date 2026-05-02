package com.sfryslie.twobuttons

import com.sfryslie.twobuttons.config.ExperimentProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(ExperimentProperties::class)
class TwoButtonsApplication

fun main(args: Array<String>) {
    runApplication<TwoButtonsApplication>(*args)
}

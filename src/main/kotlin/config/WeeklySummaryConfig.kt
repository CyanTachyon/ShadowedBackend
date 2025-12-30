package moe.tachyon.shadowed.config

import com.charleskorn.kaml.YamlComment
import kotlinx.serialization.Serializable
import moe.tachyon.shadowed.service.WeeklySummaryService

@Serializable
data class WeeklySummaryConfig(
    @YamlComment("是否启用每周自动发送总结广播")
    val enableWeeklySummary: Boolean = true,

    @YamlComment(
        "广播发送时间, 格式: \"D:HH:mm\", 其中D为星期几(1-7, 7表示星期天), " +
        "HH为小时(00-23), mm为分钟(00-59)。空字符串表示禁用该功能"
    )
    val weeklySummaryTime: String = "7:09:00"
)

var weeklySummaryConfig: WeeklySummaryConfig by config(
    "weekly_summary.yml",
    WeeklySummaryConfig(enableWeeklySummary = true, weeklySummaryTime = "0:09:00"),
    readonly = false,
    { _, _ ->
        WeeklySummaryService.stop()
        WeeklySummaryService.start()
    }
)
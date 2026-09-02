package dev.local.physicalmemory

import dev.local.physicalmemory.domain.parser.Command
import dev.local.physicalmemory.domain.parser.CommandParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CommandParserTest(private val input: String, private val expected: Command) {
    @Test fun parsesDeterministically() { assertEquals(expected, CommandParser().parse(input)) }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0}")
        fun cases(): List<Array<Any>> = listOf(
            "钥匙放在玄关柜" to Command.Store("钥匙", "玄关柜"),
            "钥匙放到桌子上" to Command.Store("钥匙", "桌子上"),
            "护照放进抽屉" to Command.Store("护照", "抽屉"),
            "相机电池放书包里" to Command.Store("相机电池", "书包里"),
            "护照放进第二个抽屉" to Command.Store("护照", "第二个抽屉"),
            "钥匙在哪" to Command.Find("钥匙"),
            "钥匙在哪里" to Command.Find("钥匙"),
            "护照放哪了" to Command.Find("护照"),
            "相机在哪儿" to Command.Find("相机"),
            "相机电池在哪儿" to Command.Find("相机电池"),
            "钥匙放在哪" to Command.Find("钥匙"),
            "钥匙放在哪里" to Command.Find("钥匙"),
            "“  钥匙在哪？ ”" to Command.Find("钥匙"),
            "　钥匙　放在　玄关柜。　" to Command.Store("钥匙", "玄关柜"),
            "钥 匙 放 在 玄 关 柜！" to Command.Store("钥匙", "玄关柜"),
            "钥匙在哪?!" to Command.Find("钥匙"),
            "AirPods  Pro 放在 study  desk." to Command.Store("AirPods Pro", "study desk"),
            "SD卡放相机包" to Command.Store("SD卡", "相机包"),
            "连花清瘟在放药的柜子里" to Command.Store("连花清瘟", "放药的柜子里"),
            "连花清瘟在哪" to Command.Find("连花清瘟"),
            "连花清瘟在哪里" to Command.Find("连花清瘟"),
            "  “连花清瘟 在 放药的柜子里。”  " to Command.Store("连花清瘟", "放药的柜子里"),
            "连花清瘟放在放药的柜子里" to Command.Store("连花清瘟", "放药的柜子里"),
            "连花清瘟放到放药的柜子里" to Command.Store("连花清瘟", "放药的柜子里"),
            "电池在存放杂物的箱子里" to Command.Store("电池", "存放杂物的箱子里"),
            "钥匙在玄关柜" to Command.Store("钥匙", "玄关柜"),
            "护照在第二个抽屉" to Command.Store("护照", "第二个抽屉"),
            "钥匙在" to Command.Unknown,
            "在柜子里" to Command.Unknown,
            "钥匙在什么地方" to Command.Unknown,
            "钥匙在哪个柜子" to Command.Unknown,
            "钥匙在吗" to Command.Unknown,
            "钥匙在抽屉里吗" to Command.Unknown,
            "钥匙在抽屉里？" to Command.Unknown,
            "钥匙在柜子护照在抽屉" to Command.Unknown,
            "" to Command.Unknown,
            " \t\n　" to Command.Unknown,
            "你好世界" to Command.Unknown,
            "？！。" to Command.Unknown,
            "放在玄关柜" to Command.Unknown,
            "钥匙放在" to Command.Unknown,
            "钥匙放到" to Command.Unknown,
            "护照放进" to Command.Unknown,
            "钥匙放" to Command.Unknown,
            "在哪" to Command.Unknown,
            "钥匙放哪" to Command.Unknown,
            "钥匙放在玄关柜，护照放抽屉" to Command.Unknown,
            "钥匙放在柜子护照放抽屉" to Command.Unknown,
            ("钥".repeat(81) + "在哪") to Command.Unknown,
            ("钥匙放在" + "柜".repeat(201)) to Command.Unknown,
            "钥".repeat(513) to Command.Unknown,
        ).map { arrayOf(it.first, it.second) }
    }
}

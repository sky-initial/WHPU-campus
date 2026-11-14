package com.zzupocket.app.data.whpu

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

internal object ElectricityFixture {
    val levels = listOf(
        WhpuElectricityLevel("campus", "校区", 1), WhpuElectricityLevel("building", "楼栋", 2),
        WhpuElectricityLevel("floor", "楼层", 3), WhpuElectricityLevel("room", "房间", 4),
    )
    val room = WhpuElectricityRoom(WhpuCardOwner("101"), WHPU_GENERAL_ELECTRICITY,
        levels.zip(listOf("示例校区", "示例楼照明", "示例层", "示例房间")) { field, name ->
            WhpuElectricityStep(field, WhpuElectricityOption(name, "test-${field.code}&$name"))
        })
    fun directory(fields: String = """{"code":"campus","name":"校区","level":1},
        {"code":"building","name":"楼栋","level":2},{"code":"floor","name":"楼层","level":3},
        {"code":"room","name":"房间","level":4}""", options: String =
        """{"name":"示例校区","value":"test-campus&示例校区"}""") =
        """{"code":200,"msg":"success","map":{"total":[$fields],"data":[$options]}}"""
    fun balance(amount: String = "12.34", roomId: String = "test-room", building: String = "test-building") =
        """{"code":200,"map":{"showData":{"信息":"当前房间余额为${amount}元"},"data":{
        "area":"test-campus","areaName":"示例校区","building":"$building","buildingName":"示例楼照明",
        "floor":"test-floor","floorName":"示例层","room":"$roomId","roomName":"示例房间",
        "account":"not-a-campus-card-account","aid":"unused"}}}"""
}

class WhpuElectricityParserTest {
    @Test fun directoryRetainsServerLevelNamesAndOpaqueValues() {
        val result = WhpuElectricityParser.directory(ElectricityFixture.directory())
        assertEquals(ElectricityFixture.levels, result.levels)
        assertEquals("test-campus&示例校区", result.options.single().value)
    }

    @Test fun codeBuildingMayMeanFloorInAnotherGroup() {
        val result = WhpuElectricityParser.directory(ElectricityFixture.directory(fields = """
            {"code":"room","name":"房间","level":3},
            {"code":"campus","name":"公寓","level":1},
            {"code":"building","name":"楼层","level":2}
        """))
        assertEquals(listOf("公寓", "楼层", "房间"), result.levels.map { it.name })
        assertEquals("building", result.levels[1].code)
    }

    @Test fun emptyDirectoryIsDistinctFromMissingDirectory() {
        assertTrue(WhpuElectricityParser.directory(ElectricityFixture.directory(options = "")).options.isEmpty())
        fails { WhpuElectricityParser.directory("""{"code":200,"map":{"total":[]}}""") }
    }

    @Test fun malformedHierarchyCannotOverwriteProtocolOrSkipLevels() {
        for (fields in listOf(
            """{"code":"feeitemid","name":"房间","level":1}""",
            """{"code":"room","name":"房间","level":2}""",
            """{"code":"room","name":"房间","level":1},{"code":"room","name":"重复","level":2}""",
        )) fails { WhpuElectricityParser.directory(ElectricityFixture.directory(fields = fields)) }
    }

    @Test fun duplicateOptionIdentityIsNotACompleteDirectory() {
        val option = """{"name":"示例","value":"same"}"""
        fails { WhpuElectricityParser.directory(ElectricityFixture.directory(options = "$option,$option")) }
    }

    @Test fun convertsVerifiedYuanTextToExactFenIncludingZeroAndNegative() {
        for ((yuan, fen) in mapOf("12.34" to 1234L, "0" to 0L, "8.5" to 850L, "-2.10" to -210L)) {
            assertEquals(fen, WhpuElectricityParser.balance(ElectricityFixture.balance(yuan), ElectricityFixture.room))
        }
    }

    @Test fun unknownUnitsOrTextNeverBecomeZeroOrElectricity() {
        for (text in listOf("剩余电量12.34度", "查询失败", "当前房间余额为NaN元", "当前房间余额为12.34美元")) {
            fails { WhpuElectricityParser.balance(
                ElectricityFixture.balance().replace("当前房间余额为12.34元", text), ElectricityFixture.room) }
        }
    }

    @Test fun excessPrecisionAndOverflowDoNotRoundOrWrap() {
        for (amount in listOf("0.001", "999999999999999999999999")) {
            fails { WhpuElectricityParser.balance(ElectricityFixture.balance(amount), ElectricityFixture.room) }
        }
    }

    @Test fun roomAndMeterContextMustMatchEvenWhenBalanceLooksValid() {
        fails { WhpuElectricityParser.balance(ElectricityFixture.balance(roomId = "other-room"), ElectricityFixture.room) }
        fails { WhpuElectricityParser.balance(ElectricityFixture.balance(building = "other-meter"), ElectricityFixture.room) }
        fails { WhpuElectricityParser.balance(ElectricityFixture.balance().replace("示例房间", "其他房间"), ElectricityFixture.room) }
    }

    @Test fun contextChecksNamesWithEmbeddedAmpersandWithoutSplittingThemAgain() {
        val original = ElectricityFixture.room
        val room = original.copy(steps = original.steps.map { step ->
            if (step.field.code == "room") step.copy(option = WhpuElectricityOption("示例&房间", "test-room&示例&房间")) else step
        })
        assertEquals(1234L, WhpuElectricityParser.balance(
            ElectricityFixture.balance().replace("示例房间", "示例&房间"), room))
    }

    @Test fun unverifiedProjectOrIncompleteSelectionCannotUseThisBalanceMapping() {
        fails { WhpuElectricityParser.balance(ElectricityFixture.balance(), ElectricityFixture.room.copy(feeItemId = "999")) }
        fails { WhpuElectricityParser.balance(ElectricityFixture.balance(), ElectricityFixture.room.copy(steps = ElectricityFixture.room.steps.dropLast(1))) }
    }

    @Test fun serviceMessageWithoutBalanceIsUnavailableNotEmptySuccess() {
        val error = runCatching { WhpuElectricityParser.balance(
            """{"code":200,"map":{"tipinfo":"private-service-detail"}}""", ElectricityFixture.room) }.exceptionOrNull()
        assertTrue(error is WhpuElectricityException.Unavailable)
        assertFalse(error.toString().contains("private-service-detail"))
    }

    @Test fun businessUnauthorizedAtHttpSuccessIsSessionExpired() {
        val error = runCatching { WhpuElectricityParser.directory("""{"code":401,"success":false,"data":null,"msg":"private"}""") }.exceptionOrNull()
        assertTrue(error is WhpuElectricityException.SessionExpired)
        val business = runCatching { WhpuElectricityParser.directory("""{"code":503,"msg":"private"}""") }.exceptionOrNull()
        assertEquals(503, (business as WhpuElectricityException.Business).code)
        assertFalse(business.toString().contains("private"))
    }

    @Test fun malformedPayloadAndCancellationKeepTheirMeaning() {
        fails { WhpuElectricityParser.directory("<html>private</html>") }
        assertTrue(runCatching { WhpuElectricityParser.parse { throw CancellationException() } }.exceptionOrNull() is CancellationException)
    }

    private fun fails(block: () -> Any) {
        assertTrue(runCatching(block).exceptionOrNull() is WhpuElectricityException.Parse)
    }
}

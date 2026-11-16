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
    val lightingLevels = listOf(
        WhpuElectricityLevel("building", "楼栋", 1), WhpuElectricityLevel("floor", "楼层", 2),
        WhpuElectricityLevel("room", "房间", 3),
    )
    val lightingRoom = WhpuElectricityRoom(WhpuCardOwner("101"), WHPU_JINYINHU_LIGHTING,
        lightingLevels.zip(listOf("示例照明楼", "示例二层", "示例房间")) { field, name ->
            // Distinct building/floor identities detect an accidentally swapped mapping.
            WhpuElectricityStep(field, WhpuElectricityOption(name, "lighting-${field.code}&$name"))
        })
    val remainingLevels = listOf(
        WhpuElectricityLevel("campus", "公寓", 1), WhpuElectricityLevel("building", "楼层", 2),
        WhpuElectricityLevel("room", "房间", 3),
    )
    val remainingRoom = WhpuElectricityRoom(WhpuCardOwner("101"), WHPU_CHANGQING_JINHU_ELECTRICITY, listOf(
        WhpuElectricityStep(remainingLevels[0], WhpuElectricityOption("示例公寓", "10001")),
        WhpuElectricityStep(remainingLevels[1], WhpuElectricityOption("示例楼层", "20002")),
        WhpuElectricityStep(remainingLevels[2], WhpuElectricityOption("示例房间", "30003")),
    ))
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
    fun lightingDirectory() = directory(fields = """{"code":"building","name":"楼栋","level":1},
        {"code":"floor","name":"楼层","level":2},{"code":"room","name":"房间","level":3}""",
        options = """{"name":"示例照明楼","value":"lighting-building&示例照明楼"}""")
    fun lightingBalance(amount: String = "12.34") =
        """{"code":200,"map":{"showData":{"信息":"当前房间当前剩余金额${amount}元"},"data":{
        "building":"lighting-building","buildingName":"示例照明楼",
        "floor":"lighting-floor","floorName":"示例二层","room":"lighting-room","roomName":"示例房间"}}}"""
    fun remainingDirectory() = directory(fields = """{"code":"campus","name":"公寓","level":1},
        {"code":"building","name":"楼层","level":2},{"code":"room","name":"房间","level":3}""",
        options = """{"name":"示例公寓","value":"10001"}""")
    fun remainingBalance(number: String = "12.3400") =
        """{"code":200,"map":{"showData":{"当前剩余电量":"$number"},"data":{
        "campusid":"10001","campus":"示例公寓","buildingid":"20002","building":"示例楼层",
        "roomid":"30003","room":"示例房间"}}}"""
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

    @Test fun generalGroupKeepsCentPrecisionInNormalizedYuanIncludingZeroAndNegative() {
        for ((yuan, normalized) in mapOf("12.34" to "12.34", "0" to "0.00", "8.5" to "8.50", "-2.10" to "-2.10")) {
            assertEquals(WhpuElectricityValue(normalized, WhpuElectricityReadingKind.YUAN),
                WhpuElectricityParser.balance(ElectricityFixture.balance(yuan), ElectricityFixture.room))
        }
    }

    @Test fun lightingGroupUsesThreeLevelsAndItsVerifiedYuanTemplate() {
        assertEquals(ElectricityFixture.lightingLevels,
            WhpuElectricityParser.directory(ElectricityFixture.lightingDirectory()).levels)
        for ((yuan, normalized) in mapOf(
            "12.34" to "12.34", "0" to "0.00", "8.5" to "8.50", "-2.10" to "-2.10",
            "12.3456" to "12.3456", "-0.0001" to "-0.0001", "12.345600" to "12.3456",
            "999999999999999999999999" to "999999999999999999999999.00",
            "999999999999999999999999.1234" to "999999999999999999999999.1234",
        )) {
            assertEquals(WhpuElectricityValue(normalized, WhpuElectricityReadingKind.YUAN), WhpuElectricityParser.balance(
                ElectricityFixture.lightingBalance(yuan), ElectricityFixture.lightingRoom))
        }
    }

    @Test fun lightingAmountRejectsWrongUnitsAndOtherTemplates() {
        for (text in listOf("当前房间余额为12.34元", "当前房间当前剩余金额12.34度", "12.34",
            "当前房间当前剩余金额NaN元", "当前房间当前剩余金额12.34美元")) {
            fails { WhpuElectricityParser.balance(ElectricityFixture.lightingBalance()
                .replace("当前房间当前剩余金额12.34元", text), ElectricityFixture.lightingRoom) }
        }
        fails { WhpuElectricityParser.balance(ElectricityFixture.balance()
            .replace("当前房间余额为12.34元", "当前房间当前剩余金额12.34元"), ElectricityFixture.room) }
    }

    @Test fun lightingChecksEverySelectedIdAndNameIndependently() {
        for (field in listOf("building", "floor", "room")) {
            fails { WhpuElectricityParser.balance(ElectricityFixture.lightingBalance()
                .replace("lighting-$field", "different-$field"), ElectricityFixture.lightingRoom) }
        }
        for (name in listOf("示例照明楼", "示例二层", "示例房间")) {
            fails { WhpuElectricityParser.balance(ElectricityFixture.lightingBalance()
                .replace(name, "另一个$name"), ElectricityFixture.lightingRoom) }
        }
        val swapped = ElectricityFixture.lightingBalance()
            .replace("lighting-building", "temporary-building")
            .replace("lighting-floor", "lighting-building")
            .replace("temporary-building", "lighting-floor")
        fails { WhpuElectricityParser.balance(swapped, ElectricityFixture.lightingRoom) }
    }

    @Test fun lightingSelectionCannotUseTheOtherGroupsHierarchy() {
        fails { WhpuElectricityParser.balance(ElectricityFixture.lightingBalance(),
            ElectricityFixture.room.copy(feeItemId = WHPU_JINYINHU_LIGHTING)) }
        fails { WhpuElectricityParser.balance(ElectricityFixture.lightingBalance(),
            ElectricityFixture.lightingRoom.copy(steps = ElectricityFixture.lightingRoom.steps.dropLast(1))) }
    }

    @Test fun remainingGroupKeepsConfirmedYuanPrecisionAndTrailingZeroes() {
        assertEquals(ElectricityFixture.remainingLevels,
            WhpuElectricityParser.directory(ElectricityFixture.remainingDirectory()).levels)
        for (number in listOf("12.3400", "0", "0.0000", "-0.0010", "999999999999999999999999.123456")) {
            assertEquals(WhpuElectricityValue(number, WhpuElectricityReadingKind.YUAN),
                WhpuElectricityParser.balance(ElectricityFixture.remainingBalance(number), ElectricityFixture.remainingRoom))
        }
    }

    @Test fun remainingGroupRejectsChangedLabelsUnitsAndNonStringNumbers() {
        for (number in listOf("12.34元", "12.34度", "NaN", "", "1e3", " 12.34 ")) {
            fails { WhpuElectricityParser.balance(ElectricityFixture.remainingBalance(number), ElectricityFixture.remainingRoom) }
        }
        for (value in listOf("12.3400", "null", "true", "{}", "[]")) {
            fails { WhpuElectricityParser.balance(ElectricityFixture.remainingBalance().replace("\"12.3400\"", value),
                ElectricityFixture.remainingRoom) }
        }
        fails { WhpuElectricityParser.balance(ElectricityFixture.remainingBalance().replace("当前剩余电量", "余额"),
            ElectricityFixture.remainingRoom) }
        fails { WhpuElectricityParser.balance(ElectricityFixture.lightingBalance(), ElectricityFixture.remainingRoom) }
        // A familiar yuan template still cannot give another group's hierarchy the fee 1 mapping.
        fails { WhpuElectricityParser.balance(ElectricityFixture.lightingBalance(),
            ElectricityFixture.lightingRoom.copy(feeItemId = WHPU_CHANGQING_JINHU_ELECTRICITY)) }
    }

    @Test fun remainingGroupChecksEachNumericIdAndNameIndependently() {
        for (value in listOf("10001", "20002", "30003", "示例公寓", "示例楼层", "示例房间")) {
            fails { WhpuElectricityParser.balance(ElectricityFixture.remainingBalance().replace(value, "other-$value"),
                ElectricityFixture.remainingRoom) }
        }
        val swapped = ElectricityFixture.remainingBalance().replace("10001", "temporary")
            .replace("20002", "10001").replace("temporary", "20002")
        fails { WhpuElectricityParser.balance(swapped, ElectricityFixture.remainingRoom) }
    }

    @Test fun remainingGroupRequiresCompleteHierarchyAndPureNumericSelectionIds() {
        val room = ElectricityFixture.remainingRoom
        for (value in listOf("30003&示例房间", "room-30003", "", "3.0", "-30003", " 30003")) {
            val badSteps = room.steps.dropLast(1) + room.steps.last().let { it.copy(option = it.option.copy(value = value)) }
            fails { WhpuElectricityParser.balance(ElectricityFixture.remainingBalance(), room.copy(steps = badSteps)) }
        }
        fails { WhpuElectricityParser.balance(ElectricityFixture.remainingBalance(), room.copy(steps = room.steps.dropLast(1))) }
        fails { WhpuElectricityParser.balance(ElectricityFixture.remainingBalance(),
            ElectricityFixture.room.copy(feeItemId = WHPU_CHANGQING_JINHU_ELECTRICITY)) }
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
        assertEquals(WhpuElectricityValue("12.34", WhpuElectricityReadingKind.YUAN), WhpuElectricityParser.balance(
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

package com.wx.rtc.bean

import com.google.gson.annotations.SerializedName

internal class SendCommandMessage {
    @JvmField
    @SerializedName("signal")
    var signal: String? = null

    @JvmField
    @SerializedName("app_id")
    var appId: String? = null

    @JvmField
    @SerializedName("room_id")
    var roomId: String? = null

    @JvmField
    @SerializedName("user_id")
    var userId: String? = null

    @JvmField
    @SerializedName("room_msg")
    var roomMsg: RoomMsg? = null

    @JvmField
    @SerializedName("record_cmd")
    var recordCmd: RecordCmdDTO? = null

    @SerializedName("process_cmd_list")
    var processCmdList: List<ProcessCmdListDTO>? = null

    @JvmField
    @SerializedName("call_cmd")
    var callCmd: CallCmdDTO? = null

    class RecordCmdDTO {
        @JvmField
        @SerializedName("cmd")
        var cmd: String? = null

        @JvmField
        @SerializedName("end_file_name")
        var endFileName: String? = null

        @JvmField
        @SerializedName("mix_id")
        var mixId: String? = null

        @JvmField
        @SerializedName("extra_data")
        var extraData: String? = null

        @SerializedName("need_after_asr")
        var isNeedAfterAsr: Boolean = false

        @JvmField
        @SerializedName("hospital_id")
        var hospitalId: String? = null

        @SerializedName("spk_list")
        var spkList: List<SpeakerDTO>? = null
    }


    class ProcessCmdListDTO {
        @JvmField
        @SerializedName("type")
        var type: String? = null

        @JvmField
        @SerializedName("cmd")
        var cmd: String? = null

        @JvmField
        @SerializedName("hospital_id")
        var hospitalId: String? = null

        @SerializedName("spk_list")
        var spkList: List<SpeakerDTO>? = null
    }

    class SpeakerDTO {
        @JvmField
        @SerializedName("spk_id")
        var spkId: Long? = null

        @JvmField
        @SerializedName("spk_name")
        var spkName: String? = null
    }

    class CallCmdDTO {
        @JvmField
        @SerializedName("cmd")
        var cmd: String? = null

        @JvmField
        @SerializedName("user_id")
        var userId: String? = null

        @JvmField
        @SerializedName("room_id")
        var roomId: String? = null
    }
}

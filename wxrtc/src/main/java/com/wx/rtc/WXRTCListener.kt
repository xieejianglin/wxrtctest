package com.wx.rtc

interface WXRTCListener {
    fun onError(errCode: Int, errMsg: String) {}
    fun onLogin() {}
    fun onLogout(reason: Int) {}
    fun onEnterRoom() {}
    fun onExitRoom(reason: Int) {}
    fun onRemoteUserEnterRoom(userId: String) {}
    fun onRemoteUserLeaveRoom(userId: String, reason: Int) {}
    fun onRecvRoomMsg(userId: String, cmd: String, message: String) {}
    fun onProcessResult(processData: WXRTCDef.ProcessData) {}

    fun onRecordStart(fileName: String) {}
    fun onRecordEnd(fileName: String) {}
}

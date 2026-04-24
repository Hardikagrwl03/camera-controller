package com.example.cameracontroller.interfaces

/**
 * Interface for Network Command
 * */
interface NetworkProtocolCommand {

    /**
     * Method to receive command from NetworkProtocol with value
     *
     * @param command String value
     * @param value any received value
     * */
    fun onReceive(command: String, value: Any?)
}
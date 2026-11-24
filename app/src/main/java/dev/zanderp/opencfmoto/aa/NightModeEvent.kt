
package dev.zanderp.opencfmoto.aa

import com.google.protobuf.Message
import dev.zanderp.opencfmoto.aa.proto.Sensors

/**
 * Reports the head unit's NIGHT sensor to Android Auto. Maps/Waze switch between their light and dark
 * map styles based on this, so sending is_night_mode=true is what makes the dash go dark.
 */
class NightModeEvent(isNight: Boolean)
    : AapMessage(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_EVENT_VALUE, makeProto(isNight)) {

    companion object {
        private fun makeProto(isNight: Boolean): Message =
            Sensors.SensorBatch.newBuilder()
                .addNightMode(Sensors.SensorBatch.NightData.newBuilder().setIsNightMode(isNight))
                .build()
    }
}

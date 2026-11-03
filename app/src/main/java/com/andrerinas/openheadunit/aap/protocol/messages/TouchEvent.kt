package com.andrerinas.openheadunit.aap.protocol.messages

import android.view.MotionEvent
import com.andrerinas.openheadunit.aap.AapMessage
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Input
import com.google.protobuf.Message

class TouchEvent(timeStamp: Long, action: Input.TouchEvent.PointerAction, actionIndex: Int, pointerData: Iterable<Triple<Int, Int, Int>>)
    : AapMessage(Channel.ID_INP, Input.MsgType.EVENT_VALUE, makeProto(timeStamp, action, actionIndex, pointerData)) {

    constructor(timeStamp: Long, action: Int, actionIndex: Int, pointerData: Iterable<Triple<Int, Int, Int>>)
        : this(timeStamp, Input.TouchEvent.PointerAction.forNumber(action), actionIndex, pointerData)

    constructor(timeStamp: Long, action: Input.TouchEvent.PointerAction, x: Int, y: Int)
        : this(timeStamp, action, 0, listOf(Triple(0, x, y)))

    companion object {
        fun motionEventToAction(event: MotionEvent): Input.TouchEvent.PointerAction? {
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN
                MotionEvent.ACTION_POINTER_DOWN -> Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_DOWN
                MotionEvent.ACTION_MOVE -> Input.TouchEvent.PointerAction.TOUCH_ACTION_MOVE
                MotionEvent.ACTION_UP -> Input.TouchEvent.PointerAction.TOUCH_ACTION_UP
                MotionEvent.ACTION_POINTER_UP -> Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_UP
                // Android Auto accepts only DOWN/UP/MOVE/POINTER_DOWN/POINTER_UP in AAP input.
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> null
                else -> null
            }
        }

        private fun makeProto(timeStamp: Long, action: Input.TouchEvent.PointerAction, actionIndex: Int, pointerData: Iterable<Triple<Int, Int, Int>>): Message {
            val touchEvent = Input.TouchEvent.newBuilder()
                    .also {
                        pointerData.forEach { data ->
                            it.addPointerData(
                                    Input.TouchEvent.Pointer.newBuilder().also { pointer ->
                                        pointer.pointerId = data.first
                                        pointer.x = data.second
                                        pointer.y = data.third
                                    })
                        }
                        it.actionIndex = actionIndex
                        it.action = action
                    }

            return Input.InputReport.newBuilder()
                    .setTimestamp(timeStamp * 1000000L)
                    .setTouchEvent(touchEvent).build()
        }
    }
}

package com.andrerinas.openheadunit.aap
class AapMessage(val channel: Int, val type: Int, val data: ByteArray,
                 val dataOffset: Int=0, val size: Int=data.size)

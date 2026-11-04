package com.andrerinas.openheadunit.aap

import android.content.Context
import android.os.SystemClock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.CALLS_REAL_METHODS
import kotlinx.coroutines.Job
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.MediaPlayback
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus
import com.andrerinas.openheadunit.utils.Settings
import com.google.protobuf.UnknownFieldSet
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.*

class AapPresentationTest {
    @Test fun nightOverlayReceivesMediaIndependentlyOfServiceCallbacksAndClearsAtRetirement() {
        mockStatic(SystemClock::class.java).use {
            mockConstruction(AapNavigationHelper::class.java).use {
                val scheduler = PresentationScheduler()
                val state = NightOverlayState()
                val handler = AapMessageHandlerType(
                    mock(), mock(), mock(), mock(), mock(), mock(),
                    presentation = scheduler.queue(), nightOverlayState = state
                )
                handler.handle(AapMessage(Channel.ID_MPB, 32771, MediaPlayback.MediaMetaData.newBuilder()
                    .setSong("Song").setArtist("Artist").build()))
                handler.handle(AapMessage(Channel.ID_MPB, 32769, MediaPlayback.MediaPlaybackStatus.newBuilder()
                    .setState(MediaPlayback.MediaPlaybackStatus.State.PLAYING).build()))
                scheduler.drain()
                assertEquals(NightOverlayState.Content(title = "Song", artist = "Artist", isPlaying = true), state.content.value)
                handler.close()
                scheduler.drain()
                assertEquals(NightOverlayState.Content(), state.content.value)
            }
        }
    }

    private fun presentationService(): AapService = mockStatic(SystemClock::class.java).use {
        // Placeholder metadata now checks whether the connection is Self Mode. Supply a
        // normal connection so session-retirement tests exercise the same policy as the app.
        val connection = mock<com.andrerinas.openheadunit.connection.CommManager>()
        val component = mock<com.andrerinas.openheadunit.AppComponent>()
        whenever(component.commManager).thenReturn(connection)
        val app = mock<com.andrerinas.openheadunit.App>()
        com.andrerinas.openheadunit.App::class.java.getDeclaredField("component\$delegate")
            .apply { isAccessible = true }.set(app, lazyOf(component))
        mock<AapService>(defaultAnswer = CALLS_REAL_METHODS).also { service ->
            doReturn(app).whenever(service).applicationContext
        }
    }

    @Test fun positionOnlyPacketDoesNotErasePendingPlayingState() {
        val scheduler = PresentationScheduler()
        val received = mutableListOf<MediaPlayback.MediaPlaybackStatus>()
        val playback = AapMediaPlayback(null, { received.add(it) }, scheduler.queue())
        playback.process(AapMessage(Channel.ID_MPB, 32769, MediaPlayback.MediaPlaybackStatus.newBuilder()
            .setState(MediaPlayback.MediaPlaybackStatus.State.PLAYING).setPlaybackSeconds(3).build()))
        playback.process(AapMessage(Channel.ID_MPB, 32769, MediaPlayback.MediaPlaybackStatus.newBuilder()
            .setPlaybackSeconds(0).build()))
        assertTrue(received.isEmpty())
        scheduler.drain()
        assertEquals(1, received.size)
        assertEquals(MediaPlayback.MediaPlaybackStatus.State.PLAYING, received.single().state)
        assertEquals(0, received.single().playbackSeconds)
    }

    @Test fun unknownFieldsDoNotAccumulateAndMetadataUsesLatestOwnedProto() {
        val scheduler = PresentationScheduler()
        val statuses = mutableListOf<MediaPlayback.MediaPlaybackStatus>()
        val metadata = mutableListOf<MediaPlayback.MediaMetaData>()
        val playback = AapMediaPlayback({ metadata.add(it) }, { statuses.add(it) }, scheduler.queue())
        val unknown = UnknownFieldSet.newBuilder().addField(100,
            UnknownFieldSet.Field.newBuilder().addVarint(42).build()).build()
        repeat(100) {
            playback.process(AapMessage(Channel.ID_MPB, 32769, MediaPlayback.MediaPlaybackStatus.newBuilder()
                .setUnknownFields(unknown).setPlaybackSeconds(it).build()))
            val message = AapMessage(Channel.ID_MPB, 32771, MediaPlayback.MediaMetaData.newBuilder()
                .setSong("Song $it").build())
            playback.process(message)
            // The TLS reader may immediately reuse its input array after dispatch returns.
            message.data.fill(0)
        }
        assertEquals(2, scheduler.tasks.size)
        scheduler.drain()
        assertEquals("Song 99", metadata.single().song)
        assertEquals(99, statuses.single().playbackSeconds)
        assertTrue(statuses.single().unknownFields.asMap().isEmpty())
        assertFalse(statuses.single().hasState())
    }

    @Test fun navigationUsesCapturedSnapshotAndRetirementClearsBroadcast() {
        mockConstruction(AapNavigationHelper::class.java).use { helpers ->
            val scheduler = PresentationScheduler()
            val queue = scheduler.queue()
            val settings = mock<Settings> { on { showNavigationNotifications } doReturn true }
            val nav = AapNavigation(mock<Context>(), settings, queue)
            nav.process(AapMessage(Channel.ID_NAV, NavigationStatus.MsgType.NEXTTURNDETAILS_VALUE,
                NavigationStatus.NextTurnDetail.newBuilder().setRoad("First road").build()))
            val helper = helpers.constructed().single()
            verify(helper, never()).showNotificationForSnapshot(any(), anyOrNull())
            val notification = scheduler.tasks.first { it.delay == 0L }.runnable
            scheduler.tasks.removeAll { it.runnable === notification }
            notification.run()
            val captured = argumentCaptor<AapNavigationHelper.NavigationSnapshot>()
            verify(helper).showNotificationForSnapshot(captured.capture(), isNull())
            nav.process(AapMessage(Channel.ID_NAV, NavigationStatus.MsgType.NEXTTURNDETAILS_VALUE,
                NavigationStatus.NextTurnDetail.newBuilder().setRoad("Second road").build()))
            assertEquals("First road", captured.firstValue.nextTurnDetail!!.payload.road)
            queue.close { nav.close() }
            scheduler.drain()
            val cleared = argumentCaptor<AapNavigationHelper.NavigationSnapshot>()
            verify(helper).sendFullNavigationBroadcast(cleared.capture(), eq(2))
            assertEquals(AapNavigationHelper.NavigationSnapshot(), cleared.firstValue)
            verify(helper).cancelNotification()
            verify(helper, times(1)).showNotificationForSnapshot(any(), anyOrNull())
        }
    }

    @Test fun stopReplacesPendingNavigationNotification() {
        mockConstruction(AapNavigationHelper::class.java).use { helpers ->
            val scheduler = PresentationScheduler()
            val settings = mock<Settings> { on { showNavigationNotifications } doReturn true }
            val nav = AapNavigation(mock<Context>(), settings, scheduler.queue())
            nav.process(AapMessage(Channel.ID_NAV, NavigationStatus.MsgType.NEXTTURNDETAILS_VALUE,
                NavigationStatus.NextTurnDetail.newBuilder().setRoad("Road").build()))
            nav.process(AapMessage(Channel.ID_NAV, NavigationStatus.MsgType.INSTRUMENT_CLUSTER_STOP_VALUE,
                NavigationStatus.NavigationState.getDefaultInstance()))
            scheduler.drain()
            verify(helpers.constructed().single(), never()).showNotificationForSnapshot(any(), anyOrNull())
            verify(helpers.constructed().single()).cancelNotification()
        }
    }

    @Test fun sessionCloseClearsPendingOrPublishedNavigationOnce() {
        mockStatic(SystemClock::class.java).use {
            // Pending turn, published turn, pending STOP, and already published STOP all need
            // a final empty snapshot. Repeating close must not schedule another clear.
            val scenarios = listOf(
                Triple(false, false, false), // pending turn
                Triple(true, false, false),  // published turn
                Triple(true, true, false),   // published turn with pending STOP
                Triple(true, true, true),    // STOP already published
            )
            for ((publishTurn, sendStop, publishStop) in scenarios) {
                mockConstruction(AapNavigationHelper::class.java).use { helpers ->
                    val scheduler = PresentationScheduler()
                    val handler = AapMessageHandlerType(mock(), mock(), mock(), mock(), mock(), mock(),
                        presentation = scheduler.queue())
                    val helper = helpers.constructed().single()
                    handler.handle(AapMessage(Channel.ID_NAV, NavigationStatus.MsgType.NEXTTURNDETAILS_VALUE,
                        NavigationStatus.NextTurnDetail.newBuilder().setRoad("Last road").build()))
                    if (publishTurn) scheduler.drain()
                    if (sendStop) {
                        handler.handle(AapMessage(Channel.ID_NAV,
                            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_STOP_VALUE,
                            NavigationStatus.NavigationState.getDefaultInstance()))
                        if (publishStop) scheduler.drain()
                    }
                    clearInvocations(helper)
                    handler.close()
                    handler.close()
                    verifyNoInteractions(helper) // Cleanup must remain off the reader.
                    scheduler.drain()
                    verify(helper).sendFullNavigationBroadcast(
                        eq(AapNavigationHelper.NavigationSnapshot()), eq(2))
                    verify(helper).cancelNotification()
                    verifyNoMoreInteractions(helper)
                }
            }
        }
    }

    @Test fun emptyStopSnapshotUsesExistingBroadcastClearValues() {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.getString(com.andrerinas.openheadunit.R.string.nav_action_unknown)).thenReturn("Unknown")
        var arguments: List<*>? = null
        mockConstruction(com.andrerinas.openheadunit.contract.NavigationUpdateIntent::class.java) { _, construction ->
            arguments = construction.arguments()
        }.use { intents ->
            AapNavigationHelper(context).sendFullNavigationBroadcast(AapNavigationHelper.NavigationSnapshot(), 2)
            // The public intent has no STOP event field. Its existing clear representation is
            // empty roads, UNKNOWN nextEventType, and absent distance/time/turn/destination data.
            assertEquals(listOf(null, null, "", 0, "Unknown", null, null, null, null, null, null, ""), arguments)
            verify(context).sendBroadcast(intents.constructed().single(),
                com.andrerinas.openheadunit.contract.NavigationUpdateIntent.BROADCAST_PERMISSION)
        }
    }

    @Test fun sessionWithoutNavigationDoesNotBroadcastStop() {
        mockConstruction(AapNavigationHelper::class.java).use { helpers ->
            val scheduler = PresentationScheduler()
            val handler = AapMessageHandlerType(mock(), mock(), mock(), mock(), mock(), mock(),
                presentation = scheduler.queue())
            handler.close()
            scheduler.drain()
            verify(helpers.constructed().single()).cancelNotification()
            verify(helpers.constructed().single(), never()).sendFullNavigationBroadcast(any(), any())
        }
    }

    @Test fun oldNavigationClearPrecedesReplacementTurn() {
        mockStatic(SystemClock::class.java).use {
            mockConstruction(AapNavigationHelper::class.java).use { helpers ->
                val scheduler = PresentationScheduler()
                val old = AapMessageHandlerType(mock(), mock(), mock(), mock(), mock(), mock(),
                    presentation = scheduler.queue())
                old.handle(AapMessage(Channel.ID_NAV, NavigationStatus.MsgType.NEXTTURNDETAILS_VALUE,
                    NavigationStatus.NextTurnDetail.newBuilder().setRoad("Old road").build()))
                // Simulate a task already removed from Main's queue, but not yet run.
                val dequeued = scheduler.tasks.removeAt(0).runnable
                old.close()
                val replacement = AapMessageHandlerType(mock(), mock(), mock(), mock(), mock(), mock(),
                    presentation = scheduler.queue())
                replacement.handle(AapMessage(Channel.ID_NAV, NavigationStatus.MsgType.NEXTTURNDETAILS_VALUE,
                    NavigationStatus.NextTurnDetail.newBuilder().setRoad("New road").build()))
                dequeued.run()
                scheduler.drain()
                val oldHelper = helpers.constructed()[0]
                val newHelper = helpers.constructed()[1]
                val order = inOrder(oldHelper, newHelper)
                order.verify(oldHelper).sendFullNavigationBroadcast(
                    eq(AapNavigationHelper.NavigationSnapshot()), eq(2))
                order.verify(oldHelper).cancelNotification()
                val latest = argumentCaptor<AapNavigationHelper.NavigationSnapshot>()
                order.verify(newHelper).sendFullNavigationBroadcast(latest.capture(), eq(0))
                assertEquals("New road", latest.firstValue.nextTurnDetail!!.payload.road)
                verify(oldHelper, times(1)).sendFullNavigationBroadcast(any(), any())
            }
        }
    }

    @Test fun closeDuringClaimedTurnPublishesStopAfterThatTurn() {
        mockStatic(SystemClock::class.java).use {
            mockConstruction(AapNavigationHelper::class.java).use { helpers ->
                val scheduler = PresentationScheduler()
                val handler = AapMessageHandlerType(mock(), mock(), mock(), mock(), mock(), mock(),
                    presentation = scheduler.queue())
                val helper = helpers.constructed().single()
                val events = mutableListOf<String>()
                doAnswer {
                    events.add("turn started")
                    handler.close()
                    events.add("turn finished")
                    null
                }.whenever(helper).sendFullNavigationBroadcast(any(), eq(0))
                doAnswer { events.add("stop"); null }
                    .whenever(helper).sendFullNavigationBroadcast(any(), eq(2))
                handler.handle(AapMessage(Channel.ID_NAV, NavigationStatus.MsgType.NEXTTURNDETAILS_VALUE,
                    NavigationStatus.NextTurnDetail.newBuilder().setRoad("Old road").build()))
                scheduler.drain()
                assertEquals(listOf("turn started", "turn finished", "stop"), events)
                verify(helper).cancelNotification()
            }
        }
    }

    @Test fun failingFinalBroadcastStillCancelsNotificationAndClosesMedia() {
        mockStatic(SystemClock::class.java).use {
            mockConstruction(AapNavigationHelper::class.java).use { helpers ->
                val scheduler = PresentationScheduler()
                val calls = mutableListOf<AaPresentationSession>()
                val session = AaPresentationSession()
                val handler = AapMessageHandlerType(mock(), mock(), mock(), mock(), mock(), mock(),
                    onAaPresentationClosed = { calls.add(it) }, presentationSession = session,
                    presentation = scheduler.queue())
                handler.handle(AapMessage(Channel.ID_NAV, NavigationStatus.MsgType.INSTRUMENT_CLUSTER_STOP_VALUE,
                    NavigationStatus.NavigationState.getDefaultInstance()))
                val failure = AssertionError("broadcast unavailable")
                val helper = helpers.constructed().single()
                doThrow(failure).whenever(helper).sendFullNavigationBroadcast(any(), eq(2))
                handler.close()
                scheduler.drain()
                verify(helper).cancelNotification()
                assertEquals(listOf(session), calls)
                assertEquals(listOf(failure), scheduler.errors)
            }
        }
    }

    @Test fun stoppingEitherReaderRetiresItsPresentationHandler() {
        mockStatic(SystemClock::class.java).use {
            for (bulk in listOf(false, true)) {
                val handler = mock<AapMessageHandler>()
                val reader: AapRead = if (bulk) AapReadMultipleMessages(mock(), mock(), handler)
                    else AapReadSingleMessage(mock(), mock(), handler)
                reader.stop()
                verify(handler).close()
                assertEquals(-1, reader.read())
            }
        }
    }

    @Test fun readerAcknowledgesAudioWithoutDrainingDisplayCallbacks() {
        mockStatic(SystemClock::class.java).use {
            val scheduler = PresentationScheduler()
            val displayed = mutableListOf<MediaPlayback.MediaPlaybackStatus>()
            val transport = mock<AapTransport>()
            val audio = mock<AapAudio> { on { process(any()) } doReturn true }
            val handler = AapMessageHandlerType(transport, mock(), audio, mock(), mock(), mock(),
                onAaPlaybackStatus = { status, _ -> displayed.add(status) },
                presentation = scheduler.queue())
            handler.handle(AapMessage(Channel.ID_MPB, 32769, MediaPlayback.MediaPlaybackStatus.newBuilder()
                .setState(MediaPlayback.MediaPlaybackStatus.State.PLAYING).build()))
            handler.handle(AapMessage(Channel.ID_AUD, 0, MediaPlayback.MediaMetaData.getDefaultInstance()))
            assertTrue(displayed.isEmpty())
            verify(transport).sendMediaAck(Channel.ID_AUD, 0)
            scheduler.drain()
            assertEquals(1, displayed.size)
            handler.close()
            // Closing also cancels pending UI updates, while ACK handling remains on the reader.
            handler.handle(AapMessage(Channel.ID_MPB, 32769, MediaPlayback.MediaPlaybackStatus.getDefaultInstance()))
            assertEquals(1, displayed.size)
        }
    }

    @Test fun newSessionResetsServiceStateEvenWithoutObservedDisconnect() {
        val service = presentationService()
        val method = AapService::class.java.getDeclaredMethod("acceptPresentationSession", AaPresentationSession::class.java)
            .apply { isAccessible = true }
        fun field(name: String) = AapService::class.java.getDeclaredField(name).apply { isAccessible = true }
        val notification = mock<com.andrerinas.openheadunit.main.BackgroundNotification>()
        field("mediaNotification\$delegate").set(service, lazyOf(notification))
        val first = AaPresentationSession()
        assertEquals(true, method.invoke(service, first))
        val job = Job()
        val meta = MediaPlayback.MediaMetaData.newBuilder().setSong("Old song").build()
        field("mediaMetadataDecodeJob").set(service, job)
        field("lastAaMediaMetadata").set(service, meta)
        field("lastAaPlaybackPositionMs").setLong(service, 9000)
        // Updates within a live session retain the existing snapshot.
        assertEquals(true, method.invoke(service, first))
        assertSame(meta, field("lastAaMediaMetadata").get(service))
        first.retire()
        assertEquals(false, method.invoke(service, first))
        assertSame(meta, field("lastAaMediaMetadata").get(service))
        val next = AaPresentationSession()
        assertEquals(true, method.invoke(service, next))
        assertTrue(job.isCancelled)
        verify(notification).cancel()
        assertNull(field("lastAaMediaMetadata").get(service))
        assertEquals(0L, field("lastAaPlaybackPositionMs").getLong(service))
        assertSame(next, field("aaPresentationSession").get(service))
        assertEquals(false, method.invoke(service, first))
        assertSame(next, field("aaPresentationSession").get(service))
    }


    @Test fun lateCloseCannotClearReplacementSessionAndMatchingCloseKeepsResumeState() {
        val service = presentationService()
        val close = AapService::class.java.getDeclaredMethod("onAaPresentationClosed", AaPresentationSession::class.java)
            .apply { isAccessible = true }
        fun field(name: String) = AapService::class.java.getDeclaredField(name).apply { isAccessible = true }
        val notification = mock<com.andrerinas.openheadunit.main.BackgroundNotification>()
        field("mediaNotification\$delegate").set(service, lazyOf(notification))
        val old = AaPresentationSession().also { it.retire() }
        val current = AaPresentationSession()
        val job = Job()
        val meta = MediaPlayback.MediaMetaData.newBuilder().setSong("New song").build()
        field("aaPresentationSession").set(service, current)
        field("lastAaMediaMetadata").set(service, meta)
        field("mediaMetadataDecodeJob").set(service, job)
        field("lastAaPlaybackIsPlaying").set(service, true)
        close.invoke(service, old)
        assertFalse(job.isCancelled)
        assertSame(meta, field("lastAaMediaMetadata").get(service))
        verifyNoInteractions(notification)
        current.retire()
        close.invoke(service, current)
        assertTrue(job.isCancelled)
        assertNull(field("lastAaMediaMetadata").get(service))
        assertEquals(true, field("lastAaPlaybackIsPlaying").get(service))
        verify(notification).cancel()
    }

    @Test fun failingNavigationCleanupStillClosesMediaSessionOnce() {
        mockConstruction(AapNavigationHelper::class.java).use { helpers ->
            val scheduler = PresentationScheduler()
            val calls = mutableListOf<AaPresentationSession>()
            val session = AaPresentationSession()
            val handler = AapMessageHandlerType(mock(), mock(), mock(), mock(), mock(), mock(),
                onAaPresentationClosed = { calls.add(it) }, presentationSession = session,
                presentation = scheduler.queue())
            doThrow(IllegalStateException("notification service unavailable"))
                .whenever(helpers.constructed().single()).cancelNotification()
            handler.close()
            handler.close()
            assertFalse(session.isActive)
            scheduler.drain()
            assertEquals(listOf(session), calls)
            assertEquals(1, scheduler.errors.size)
        }
    }

}

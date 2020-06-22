package com.arkivanov.mvikotlin.timetravel.client.internal

import com.arkivanov.mvikotlin.timetravel.client.internal.TimeTravelClientStoreFactory.Connector
import com.arkivanov.mvikotlin.timetravel.proto.internal.data.timetravelstateupdate.TimeTravelStateUpdate
import com.arkivanov.mvikotlin.timetravel.proto.internal.io.ReaderThread
import com.arkivanov.mvikotlin.timetravel.proto.internal.io.WriterThread
import com.badoo.reaktive.disposable.Disposable
import com.badoo.reaktive.observable.Observable
import com.badoo.reaktive.observable.ObservableEmitter
import com.badoo.reaktive.observable.observable
import com.badoo.reaktive.observable.observeOn
import com.badoo.reaktive.observable.onErrorReturn
import com.badoo.reaktive.observable.subscribeOn
import com.badoo.reaktive.scheduler.ioScheduler
import com.badoo.reaktive.scheduler.mainScheduler
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import platform.darwin.inet_addr
import platform.posix.AF_INET
import platform.posix.IPPROTO_TCP
import platform.posix.PF_INET
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.memset
import platform.posix.sockaddr_in
import platform.posix.socket

internal class TimeTravelConnector(
    private val host: String,
    private val port: Int
) : Connector {

    override fun connect(): Observable<Connector.Event> =
        observable<Connector.Event> { it.connect() }
            .onErrorReturn { Connector.Event.Error(it.message) }
            .subscribeOn(ioScheduler)
            .observeOn(mainScheduler)

    private fun ObservableEmitter<Connector.Event>.connect() {
        val socket = socket(PF_INET, SOCK_STREAM, IPPROTO_TCP)
        if (socket < 0) {
            onError(Exception("Error open socket: $errno"))
            return
        }

//        val host: CPointer<hostent>? = gethostbyname(host)
//        if (host == null) {
//            onError(Exception("Error getting host by name: $h_errno"))
//            return
//        }

        memScoped {
            val sin = alloc<sockaddr_in>()
            memset(sin.ptr, 0, sockaddr_in.size.convert())
            sin.sin_len = sizeOf<sockaddr_in>().convert()
            sin.sin_family = AF_INET.convert()
            sin.sin_port = ((port shr 8) or ((port and 0xff) shl 8)).toUShort()
            sin.sin_addr.s_addr = inet_addr(host)

            if (connect(socket, sin.ptr.reinterpret(), sockaddr_in.size.convert()) < 0) {
                onError(Exception("Error connect socket: $errno"))
                return
            }
        }

        if (isDisposed) {
            close(socket)
            return
        }

        val reader =
            ReaderThread<TimeTravelStateUpdate>(
                socket = socket,
                onRead = { onNext(Connector.Event.StateUpdate(it)) },
                onError = ::onError
            )

        val writer = WriterThread(socket = socket, onError = ::onError)

        onNext(Connector.Event.Connected(writer::submit))

        reader.start()

        setDisposable(
            Disposable {
                reader.interrupt()
                writer.interrupt()
                close(socket)
            }
        )
    }
}

package kr.han.meal.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

fun Context.isOnline():Boolean {
    val cm=getSystemService(ConnectivityManager::class.java)
    return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
}
fun connectivity(context:Context)=callbackFlow {
    val cm=context.getSystemService(ConnectivityManager::class.java)
    val callback=object:ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network:Network) {trySend(context.isOnline())}
        override fun onLost(network:Network) {trySend(context.isOnline())}
        override fun onCapabilitiesChanged(network:Network,capabilities:NetworkCapabilities) {trySend(context.isOnline())}
    }
    cm.registerDefaultNetworkCallback(callback);trySend(context.isOnline())
    awaitClose {cm.unregisterNetworkCallback(callback)}
}

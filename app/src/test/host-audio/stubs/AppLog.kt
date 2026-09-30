package com.andrerinas.openheadunit.utils
object AppLog { @Volatile var hook:((String)->Unit)?=null;val lines=java.util.concurrent.CopyOnWriteArrayList<String>();fun i(s:String,vararg a:Any){lines.add(s);hook?.invoke(s)};fun w(s:String,vararg a:Any){lines.add(s)};fun d(s:String,vararg a:Any){};fun e(s:String,vararg a:Any){lines.add(s+" "+a.joinToString())} }

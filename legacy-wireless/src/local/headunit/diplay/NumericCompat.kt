package local.headunit.diplay
object NumericCompat {
 fun unsignedLong(value:Long):String=if(value>=0)value.toString() else java.math.BigInteger.valueOf(value and Long.MAX_VALUE).setBit(63).toString()
 fun floorSeconds(ns:Long):Long {val q=ns/1000000000L;return if(ns%1000000000L<0)q-1 else q}
 fun floorNanos(ns:Long):Long {val r=ns%1000000000L;return if(r<0)r+1000000000L else r}
}

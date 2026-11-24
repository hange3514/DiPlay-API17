/* Single queued startup transfer. No retransmission after successful submit.
 * Operations are injected so timeout, cancellation and detach races can be tested
 * without a phone. Only the owning USB connection may reap this request. */
#ifndef DIPLAY_STARTUP_TRANSFER_H
#define DIPLAY_STARTUP_TRANSFER_H
#define STARTUP_UNSUPPORTED (-20001)
typedef struct {
 void *context;
 int (*submit)(void *);
 int (*reap)(void *, int *, int *);
 int (*cancel)(void *);
 int (*wait)(void *, int);
 long long (*now)(void *);
 int (*keep)(void *);
} startup_ops;
typedef struct { int submitted,polls,elapsed,cancelled,actual,status; } startup_stats;
static int startup_read_result(int result,const startup_stats *s) {
 /* A successful zero-length IN is legal. Do not hide I/O or cancellation errors. */
 if(result==-5&&s->submitted&&!s->cancelled&&s->status==0&&s->actual==0)return 0;
 return result;
}
static int startup_result(int status,int actual,int expected) {
 if(actual<0||actual>expected)return -71;
 /* A partial transfer must never be silently replayed, even on cancellation. */
 if(actual>0)return actual;
 return status ? status : -5;
}
static int startup_transfer(startup_ops *o,int length,int timeout,startup_stats *s) {
 *s=(startup_stats){0};
 long long at=o->now(o->context);
 if(!o->keep(o->context))return -125;
 int rc=o->submit(o->context);
 if(rc){
  s->status=rc;
  /* Fallback is safe ONLY if no request was ever submitted. */
  return rc==-22||rc==-25||rc==-38||rc==-95 ? STARTUP_UNSUPPORTED : rc;
 }
 s->submitted=1;
 int reason=-110,status=0,actual=0;
 for(;;){
  rc=o->reap(o->context,&status,&actual);
  s->elapsed=(int)(o->now(o->context)-at);
  if(rc==0){s->status=status;s->actual=actual;return startup_result(status,actual,length);}
  if(rc!=-11&&rc!=-4){reason=rc;break;}
  if(!o->keep(o->context)){reason=-125;break;}
  int left=timeout-s->elapsed;
  if(left<=0)break;
  s->polls++;
  rc=o->wait(o->context,left<25?left:25);
  if(rc<0&&rc!=-4){reason=rc;break;}
 }
 /* Linux USBDEVFS_DISCARDURB calls usb_kill_urb synchronously. A successful
  * discard has queued completion before returning. EINVAL may mean completion
  * won the race. Reap either case before letting stack storage go out of scope.
  * ENODEV completion cannot be reaped on this kernel; connection close frees it.
  * Any unexpected failure is fatal; caller must not submit/reap on this fd again. */
 s->cancelled=1;
 int discarded=o->cancel(o->context);
 for(int n=0;n<4;n++){
  rc=o->reap(o->context,&status,&actual);
  if(rc!=-4)break;
 }
 s->elapsed=(int)(o->now(o->context)-at);s->status=reason;s->actual=actual;
 if(rc==0){
  s->status=status;
  if(actual>0)return startup_result(status,actual,length);
  if(status==0||status==-2||status==-104)return reason;
  return status;
 }
 if(rc==-19||discarded==-19)return -19;
 return -71;
}
#endif

#include "../native/startup_transfer.h"
/* Fault-injected kernel model. Exit at assertion line; no phone, shell or USB. */
#define CHECK(x) do{if(!(x))return __LINE__;}while(0)
typedef struct {
 int time,ready,stop,submits,reaps,cancels,polls,submitted,completed;
 int submit_error,discard_error,reap_error,completion_status,actual;
 int race,eintr;
} fake;
static int submit_fake(void *p){fake *f=p;f->submits++;if(!f->submit_error)f->submitted=1;return f->submit_error;}
static int reap_fake(void *p,int *status,int *actual){
 fake *f=p;f->reaps++;
 if(f->reap_error)return f->reap_error;
 if(f->eintr>0){f->eintr--;return -4;}
 if(!f->completed&&f->time<f->ready)return -11;
 *status=f->completion_status;*actual=f->actual;return 0;
}
static int cancel_fake(void *p){
 fake *f=p;f->cancels++;
 if(f->discard_error)return f->discard_error;
 f->completed=1;
 if(f->race){f->actual=90;f->completion_status=0;return -22;}
 f->completion_status=-2;return 0;
}
static int wait_fake(void *p,int ms){fake *f=p;f->polls++;f->time+=ms;return 0;}
static long long now_fake(void *p){return ((fake*)p)->time;}
static int keep_fake(void *p){fake *f=p;return f->time<f->stop;}
static fake initial(void){fake f={0};f.ready=100000;f.stop=100000;return f;}
static int run(fake *f,startup_stats *s){startup_ops o={f,submit_fake,reap_fake,cancel_fake,wait_fake,now_fake,keep_fake};return startup_transfer(&o,90,15000,s);}
int main(void){
 startup_stats s;fake f;
 f=initial();f.ready=0;f.actual=90;CHECK(run(&f,&s)==90);CHECK(f.submits==1&&f.cancels==0&&f.polls==0);
 f=initial();f.ready=9600;f.actual=90;CHECK(run(&f,&s)==90);CHECK(f.submits==1&&f.cancels==0&&f.time==9600);
 f=initial();CHECK(run(&f,&s)==-110);CHECK(f.time==15000&&f.submits==1&&f.cancels==1&&f.polls==600);
 f=initial();f.stop=101;CHECK(run(&f,&s)==-125);CHECK(f.time==125&&f.submits==1&&f.cancels==1);
 f=initial();f.stop=0;CHECK(run(&f,&s)==-125);CHECK(f.submits==0&&f.cancels==0);
 for(int n=0;n<4;n++){int e[]={-22,-25,-38,-95};f=initial();f.submit_error=e[n];CHECK(run(&f,&s)==STARTUP_UNSUPPORTED);CHECK(!s.submitted&&f.cancels==0&&f.reaps==0);}
 f=initial();f.submit_error=-19;CHECK(run(&f,&s)==-19);CHECK(!s.submitted);
 f=initial();f.ready=25;f.actual=40;f.completion_status=-110;CHECK(run(&f,&s)==40);CHECK(f.submits==1);
 f=initial();f.stop=25;f.actual=40;CHECK(run(&f,&s)==40);CHECK(f.submits==1&&f.cancels==1);
 f=initial();f.stop=25;f.race=1;CHECK(run(&f,&s)==90);CHECK(f.cancels==1);
 f=initial();f.reap_error=-19;f.discard_error=-19;CHECK(run(&f,&s)==-19);CHECK(f.cancels==1);
 f=initial();f.reap_error=-5;f.discard_error=-5;CHECK(run(&f,&s)==-71);CHECK(f.cancels==1);
 f=initial();f.ready=25;f.actual=91;CHECK(run(&f,&s)==-71);
 f=initial();f.ready=25;f.actual=-1;CHECK(run(&f,&s)==-71);
 f=initial();f.ready=25;f.completion_status=-32;CHECK(run(&f,&s)==-32);CHECK(f.submits==1);
 f=initial();f.ready=25;CHECK(run(&f,&s)==-5);
 f=initial();f.ready=25;f.actual=90;f.eintr=3;CHECK(run(&f,&s)==90);CHECK(f.submits==1);
 /* Hundreds of cancel/reconnect rounds never turn one request into retries. */
 for(int n=0;n<200;n++){f=initial();f.stop=n%10+1;CHECK(run(&f,&s)==-125);CHECK(f.submits==1&&f.cancels==1);}
 /* IN requests accept short, full and zero packets, preserving cancel status. */
 for(int bytes=0;bytes<=16384;bytes+=512){
  f=initial();f.ready=25;f.actual=bytes;
  startup_ops o={&f,submit_fake,reap_fake,cancel_fake,wait_fake,now_fake,keep_fake};
  int result=startup_transfer(&o,16384,15000,&s);
  CHECK(startup_read_result(result,&s)==bytes);CHECK(f.submits==1&&f.cancels==0);
 }
 f=initial();f.stop=25;CHECK(startup_read_result(run(&f,&s),&s)==-125);
 f=initial();f.ready=25;f.completion_status=-5;CHECK(startup_read_result(run(&f,&s),&s)==-5);
 f=initial();f.ready=25;f.actual=140;
 {startup_ops o={&f,submit_fake,reap_fake,cancel_fake,wait_fake,now_fake,keep_fake};CHECK(startup_transfer(&o,16384,15000,&s)==140);}
 return 0;
}

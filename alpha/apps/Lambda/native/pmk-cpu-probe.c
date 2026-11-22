/* Synthetic PBKDF2-HMAC-SHA1 benchmark, not a password recovery command. */
#define _POSIX_C_SOURCE 200809L
#include <openssl/sha.h>
#include <openssl/evp.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/auxv.h>
#include <asm/hwcap.h>

static void be32(unsigned char *p,uint32_t x) {p[0]=x>>24;p[1]=x>>16;p[2]=x>>8;p[3]=x;}
static void state(SHA_CTX *c,const SHA_CTX *s) {c->h0=s->h0;c->h1=s->h1;c->h2=s->h2;c->h3=s->h3;c->h4=s->h4;}
static void digest(const SHA_CTX *c,unsigned char *p) {be32(p,c->h0);be32(p+4,c->h1);be32(p+8,c->h2);be32(p+12,c->h3);be32(p+16,c->h4);}
static void pmk(const unsigned char *pass,size_t length,const unsigned char *ssid,size_t n,unsigned char out[32]) {
    unsigned char pad[64],u[20],v[20],block[64]={0},salt[36];
    SHA_CTX in,outseed,c;
    memset(pad,0x36,64);for(size_t i=0;i<length;i++)pad[i]^=pass[i];
    SHA1_Init(&in);SHA1_Update(&in,pad,64);
    memset(pad,0x5c,64);for(size_t i=0;i<length;i++)pad[i]^=pass[i];
    SHA1_Init(&outseed);SHA1_Update(&outseed,pad,64);
    memcpy(salt,ssid,n);block[20]=0x80;be32(block+60,84*8);
    for(unsigned part=1;part<=2;part++) {
        be32(salt+n,part);c=in;SHA1_Update(&c,salt,n+4);SHA1_Final(u,&c);
        c=outseed;SHA1_Update(&c,u,20);SHA1_Final(u,&c);memcpy(v,u,20);
        for(unsigned j=1;j<4096;j++) {
            memcpy(block,u,20);state(&c,&in);SHA1_Transform(&c,block);digest(&c,block);
            state(&c,&outseed);SHA1_Transform(&c,block);digest(&c,u);
            for(int k=0;k<20;k++)v[k]^=u[k];
        }
        memcpy(out+(part-1)*20,v,part==1?20:12);
    }
}
typedef struct {int id,threads,count;unsigned checksum;} task;
static void *worker(void *arg) {
    task *t=arg;unsigned char key[32];char pass[32];
    for(int i=t->id;i<t->count;i+=t->threads) {
        int n=snprintf(pass,sizeof(pass),"Bench%010d",i);
        pmk((unsigned char *)pass,n,(unsigned char *)"LambdaBenchmark",15,key);
        t->checksum+=key[0];
    }return NULL;
}
static double now(void) {struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec+t.tv_nsec*1e-9;}
int main(int argc,char **argv) {
    int threads=argc==2?atoi(argv[1]):8;
    if(threads!=1&&threads!=4&&threads!=8)return 64;
    /* Vary password/SSID length and bytes; compare all 32 PMK bytes. */
    for(int i=0;i<100;i++) {
        unsigned char pass[64],salt[32],a[32],b[32];int len=8+i%56,sl=i%33;
        for(int j=0;j<len;j++)pass[j]=(unsigned char)(33+(j*13+i)%90);
        for(int j=0;j<sl;j++)salt[j]=(unsigned char)(j*19+i);
        pmk(pass,len,salt,sl,a);
        if(!PKCS5_PBKDF2_HMAC_SHA1((char *)pass,len,salt,sl,4096,32,b)||memcmp(a,b,32)){
            printf("{\"valid\":false,\"vector\":%d}\n",i);return 1;
        }
    }
    task tasks[8];pthread_t ids[8];double begin=now();unsigned checksum=0;
    for(int i=0;i<threads;i++){tasks[i]=(task){i,threads,4800,0};if(pthread_create(ids+i,NULL,worker,tasks+i))return 70;}
    for(int i=0;i<threads;i++){pthread_join(ids[i],NULL);checksum+=tasks[i].checksum;}
    double seconds=now()-begin;
    printf("{\"valid\":true,\"vectors\":100,\"hwcap_sha1\":%s,\"threads\":%d,\"pmks\":4800,\"seconds\":%.6f,\"pmks_per_second\":%.3f,\"checksum\":%u}\n",
        (getauxval(AT_HWCAP)&HWCAP_SHA1)?"true":"false",threads,seconds,4800/seconds,checksum);
    return 0;
}

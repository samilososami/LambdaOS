/* Private, bounded PMK batch cache. MIC verification stays in Aircrack. */
#ifndef LAMBDA_PMK_CACHE_H
#define LAMBDA_PMK_CACHE_H
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
typedef struct {char password[16];unsigned char pmk[32];} lambda_cached_key;
static lambda_cached_key lambda_cache[19200];
static unsigned char lambda_cache_ssid[32];
static unsigned lambda_cache_count,lambda_cache_ssid_len;
static int lambda_cache_enabled;
static pthread_once_t lambda_cache_once=PTHREAD_ONCE_INIT;
static unsigned lambda_u32(const unsigned char *p){return p[0]|(p[1]<<8)|(p[2]<<16)|((unsigned)p[3]<<24);}
static int lambda_compare(const void *a,const void *b){return strcmp(((const lambda_cached_key *)a)->password,((const lambda_cached_key *)b)->password);}
static void lambda_cache_fail(void){fputs("LAMBDA_CACHE invalid\n",stderr);exit(74);}
static void lambda_cache_load(void){
    const char *path=getenv("LAMBDA_PMK_CACHE");if(!path)return;lambda_cache_enabled=1;
    FILE *f=fopen(path,"rb");unsigned char head[48];
    if(!f||fread(head,1,48,f)!=48||memcmp(head,"LPMKC01",8))lambda_cache_fail();
    lambda_cache_ssid_len=lambda_u32(head+8);lambda_cache_count=lambda_u32(head+12);
    if(lambda_cache_ssid_len<1||lambda_cache_ssid_len>32||lambda_cache_count<1||lambda_cache_count>19200)lambda_cache_fail();
    memcpy(lambda_cache_ssid,head+16,32);
    if(fread(lambda_cache,sizeof(lambda_cached_key),lambda_cache_count,f)!=lambda_cache_count||fgetc(f)!=EOF)lambda_cache_fail();fclose(f);
    for(unsigned i=0;i<lambda_cache_count;i++){
        size_t n=strnlen(lambda_cache[i].password,16);if(n<8||n>15)lambda_cache_fail();
        for(size_t j=0;j<n;j++)if((unsigned char)lambda_cache[i].password[j]<32||(unsigned char)lambda_cache[i].password[j]>126)lambda_cache_fail();
    }
    qsort(lambda_cache,lambda_cache_count,sizeof(lambda_cached_key),lambda_compare);
    for(unsigned i=1;i<lambda_cache_count;i++)if(!strcmp(lambda_cache[i-1].password,lambda_cache[i].password)&&memcmp(lambda_cache[i-1].pmk,lambda_cache[i].pmk,32))lambda_cache_fail();
    fprintf(stderr,"LAMBDA_CACHE ready %u\n",lambda_cache_count);
}
static int lambda_cache_pmk(const unsigned char *key,size_t length,const unsigned char *ssid,size_t n,unsigned char out[32]){
    pthread_once(&lambda_cache_once,lambda_cache_load);if(!lambda_cache_enabled)return 0;
    // Aircrack passes a full SIMD lane including empty padding and its four-FF
    // end-of-stream marker. Keep the original calculation for those non-keys.
    if(length==0||(length>=4&&key[0]==255&&key[1]==255&&key[2]==255&&key[3]==255))return 0;
    if(n!=lambda_cache_ssid_len||memcmp(ssid,lambda_cache_ssid,n)||length<8||length>15)lambda_cache_fail();
    lambda_cached_key needle={0};memcpy(needle.password,key,length);
    lambda_cached_key *found=bsearch(&needle,lambda_cache,lambda_cache_count,sizeof(lambda_cached_key),lambda_compare);
    if(!found)lambda_cache_fail();memcpy(out,found->pmk,32);return 1;
}
#endif

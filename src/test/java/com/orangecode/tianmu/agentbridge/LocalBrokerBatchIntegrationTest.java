package com.orangecode.tianmu.agentbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="danmaku.broker.it", matches="true")
class LocalBrokerBatchIntegrationTest {
 @Test void validMessagesSurvivePoisonInRealBrokerBatch() throws Exception {
  // Explicit local load-test endpoint only; never uses production configuration.
  var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13316/tianmu?useSSL=false&allowPublicKeyRetrieval=true","root","loadtest"));
  assertEquals("loadtest",db.queryForObject("SELECT title FROM video WHERE video_id=99",String.class));
  String prefix="broker-it-"+UUID.randomUUID()+"-";
  long first=System.currentTimeMillis()*1000;
  var producer=new DefaultMQProducer("load-batch-it-"+UUID.randomUUID()); producer.setNamesrvAddr("127.0.0.1:19876");
  try {
   producer.start(); var messages=new ArrayList<Message>();
   for(int i=0;i<10;i++) messages.add(new Message("tianmu-topic",("{\"bulletId\":"+(first+i)+",\"videoId\":99,\"userId\":1,\"content\":\""+prefix+i+"\",\"color\":\"#ffffff\",\"playbackTime\":0,\"createTime\":"+System.currentTimeMillis()+"}").getBytes(StandardCharsets.UTF_8)));
   // A permanently invalid event between valid messages exercises batch fallback.
   messages.add(4,new Message("tianmu-topic","broken json".getBytes(StandardCharsets.UTF_8)));
   producer.send(messages,producer.fetchPublishMessageQueues("tianmu-topic").get(0));
   long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30); int count=0;
   while(System.nanoTime()<end){count=db.queryForObject("SELECT COUNT(*) FROM bullet WHERE content LIKE ?",Integer.class,prefix+"%");if(count==10)break;Thread.sleep(200);}
   assertEquals(10,count,"valid messages must commit despite poison");
   assertEquals(10,db.queryForObject("SELECT COUNT(DISTINCT bullet_id) FROM bullet WHERE content LIKE ?",Integer.class,prefix+"%"));
  } finally { producer.shutdown(); }
 }
}

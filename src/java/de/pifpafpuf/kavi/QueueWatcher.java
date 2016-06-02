package de.pifpafpuf.kavi;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.log4j.Logger;

import de.pifpafpuf.kavi.offmeta.GroupMetaKey;
import de.pifpafpuf.kavi.offmeta.GroupMsgValue;
import de.pifpafpuf.kavi.offmeta.MetaKey;
import de.pifpafpuf.kavi.offmeta.OffsetInfo;
import de.pifpafpuf.kavi.offmeta.OffsetMetaKey;
import de.pifpafpuf.kavi.offmeta.OffsetMsgValue;
import de.pifpafpuf.kavi.offmeta.OffsetsKeyDeserializer;
import de.pifpafpuf.kavi.offmeta.PartitionMeta;

public class QueueWatcher {
  private static final Logger log = KafkaViewerServer.getLogger();
  public static final String TOPIC_OFFSET = "__consumer_offsets";

  private final KafkaConsumer<Object, byte[]> kafcon;
  private final KafkaConsumer<MetaKey, byte[]> offcon;

  public QueueWatcher(String hostport) {
    Properties props = new Properties();
    props.put("group.id", "some-random-group-id");
    props.put("bootstrap.servers", hostport);
    props.put("enable.auto.commit", "false");
    props.put("log.message.format.version", "0.9.0");
    kafcon = new KafkaConsumer<>(props, GeneralKeyDeserializer.KEY,
        new ByteArrayDeserializer());
    assignAllPartitions(kafcon);
    props.put("group.id", "totally-random-group-id");

    offcon = new KafkaConsumer<>(props, OffsetsKeyDeserializer.INSTANCE,
        new ByteArrayDeserializer());
    offcon.assign(assignablePartitions(offcon, TOPIC_OFFSET));
  }
  /*+******************************************************************/
  public void shutdown() {
    kafcon.close();
    offcon.close();
  }
  /*+**********************************************************************/
  private static void assignAllPartitions(KafkaConsumer<?,?> consumer) {
    List<TopicPartition> assigns = new LinkedList<>();
    for (String topic : consumer.listTopics().keySet()) {
      assigns.addAll(assignablePartitions(consumer, topic));
    }
    consumer.assign(assigns);
  }
  /*+*********************************************************************/
  public Map<String, List<PartitionMeta>> topicInfo() {
    Map<String, List<PartitionMeta>> result = new HashMap<>();
    Map<String, List<PartitionInfo>> m = kafcon.listTopics();
    for (Map.Entry<String, List<PartitionInfo>> elem : m.entrySet()) {
      List<PartitionMeta> l = result.get(elem.getKey());
      if (l==null) {
        l = new LinkedList<>();
        result.put(elem.getKey(), l);
        setOffsets(elem.getKey(), -1);
      }
      for (PartitionInfo pi : elem.getValue()) {
        long offset = kafcon.position(tpFromPi(pi));
        l.add(new PartitionMeta(pi.topic(), pi.partition(), offset+1));
      }
    }
    return result;
  }
  /*+******************************************************************/
  public List<ConsumerRecord<Object, byte[]>>
  readRecords(String topic, int offset)
  {
    assignAllPartitions(kafcon); // may have new partitions
    setOffsets(topic, offset);
    final long WAIT = 1000;
    boolean timedout = false;
    List<ConsumerRecord<Object, byte[]>> result = new LinkedList<>();
    while (!timedout) {
      long now = System.currentTimeMillis();
      ConsumerRecords<Object, byte[]> recs = kafcon.poll(WAIT);
      long later = System.currentTimeMillis();
      timedout = now+WAIT>=later;
      for (ConsumerRecord<Object, byte[]> rec : recs) {
        if (topic.equals(rec.topic())) {
          result.add(rec);
        }
      }
    }
    return result;
  }
  /*+******************************************************************/
  private void setOffsets(String topic, int offset) {
    int numPartitions = kafcon.partitionsFor(topic).size();
    List<TopicPartition> tps = new LinkedList<>();
    for (int i=0; i<numPartitions; i++) {
      tps.add(new TopicPartition(topic, i));
    }
    if (offset<0) {
      //TODO: for 0.10.0 kafcon.seekToEnd(tps);
      kafcon.seekToEnd(tps.toArray(new TopicPartition[tps.size()]));
    }

    for (int i=0; i<numPartitions; i++) {
      TopicPartition tp = tps.remove(0);
      long newOffset;
      if (offset<0) {
        newOffset = Math.max(0, kafcon.position(tp)+offset);
      } else {
        newOffset = offset;
      }
      kafcon.seek(tp, newOffset);
    }
  }
  /*+******************************************************************/
  private long getHead(OffsetMetaKey okey) {
    TopicPartition tp = new TopicPartition(okey.topic, okey.partition);
    //TODO: for 0.10.0 kafcon.seekToEnd(Collections.singletonList(tp));
    kafcon.seekToEnd(tp);
    return kafcon.position(tp);
  }
  /*+**********************************************************************/
  public void rewindOffsets(int count) {
    List<TopicPartition> tps = assignablePartitions(offcon, TOPIC_OFFSET);
    //TODO: for 0.10.0 offcon.seekToEnd(tps);
    offcon.seekToEnd(tps.toArray(new TopicPartition[tps.size()]));
    for (TopicPartition tp : offcon.assignment()) {
      long position = offcon.position(tp);
      if (position>0) {
        long newpos = Math.max(0, position-count);
        log.info("seeking "+tp+" to "+newpos);
        offcon.seek(tp,  newpos);
      }
    }
  }
  /*+**********************************************************************/
  public Map<String, OffsetInfo> getLastOffsets(long pollMillis) {
    Map<String, List<String>> groupData = new HashMap<>();

    Map<String, OffsetInfo> result = new HashMap<>();
    ConsumerRecords<MetaKey, byte[]> data;
    for (data=offcon.poll(pollMillis);
        !data.isEmpty();
        data=offcon.poll(pollMillis)) {
      for(ConsumerRecord<MetaKey, byte[]> r : data) {
        MetaKey key = r.key();
        result.remove(key.getKey()); // keep only the most recent
        if (key instanceof OffsetMetaKey) {
          OffsetMetaKey okey = (OffsetMetaKey)key;
          OffsetMsgValue value = (OffsetMsgValue)key.decodeValue(r.value());
          long tip = getHead(okey);
          OffsetInfo oinfo = new OffsetInfo(tip, okey, value);
          result.put(key.getKey(), oinfo);
          addConsumer(groupData, okey);
        } else {
          GroupMsgValue v = GroupMsgValue.decode(r.value());
          GroupMetaKey gkey = (GroupMetaKey)key;
          if (v==null) {
            List<String> deadKeys = groupData.get(gkey.group);
            if (deadKeys!=null) {
              for (String dead : deadKeys) {
                OffsetInfo oi = result.remove(dead);
                result.put(dead, oi.asDead());
              }
            }
          }
        }
      }
    }
    return result;
  }
  /*+**********************************************************************/
  private static void addConsumer(Map<String, List<String>> groupData,
                                  OffsetMetaKey okey) {
    List<String> keys = groupData.get(okey.group);
    if (keys==null) {
      keys = new LinkedList<String>();
      groupData.put(okey.group, keys);
    }
    keys.add(okey.getKey());
  }
  /*+**********************************************************************/
  private static List<TopicPartition>
  assignablePartitions(KafkaConsumer<?,?> con, String topic)
  {
    List<TopicPartition> result = new LinkedList<>();
    List<PartitionInfo> pis = con.partitionsFor(topic);
    for (PartitionInfo pi : pis) {
      result.add(tpFromPi(pi));
    }
    return result;
  }
  private static TopicPartition tpFromPi(PartitionInfo pi) {
    return new TopicPartition(pi.topic(), pi.partition());
  }
}

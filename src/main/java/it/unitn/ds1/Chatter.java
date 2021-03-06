package it.unitn.ds1;
import akka.actor.ActorRef;
import akka.actor.AbstractActor;
import java.util.Random;
import java.io.Serializable;
import akka.actor.Props;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.lang.Thread;
import java.lang.InterruptedException;
import java.util.Collections;
import java.util.Iterator;

class Chatter extends AbstractActor {

  // number of chat messages to send
  final static int N_MESSAGES = 5;
  private Random rnd = new Random();
  private List<ActorRef> group; // the list of peers (the multicast group)
  private List<ChatMsg> buffer = new ArrayList<>(); //message buffer
  private int sendCount = 0;    // number of sent messages
  private String myTopic;  // The topic I am interested in, null if no topic
  private final int id;    // ID of the current actor
  private int[] vc;        // the local vector clock

  // a buffer storing all received chat messages
  private StringBuffer chatHistory = new StringBuffer();

  /* -- Message types ------------------------------------------------------- */

  // Start message that informs every chat participant about its peers
  public static class JoinGroupMsg implements Serializable {
    private final List<ActorRef> group; // list of group members
    public JoinGroupMsg(List<ActorRef> group) {
      this.group = Collections.unmodifiableList(group);
    }
  }

  // A message requesting the peer to start a discussion on his topic
  public static class StartChatMsg implements Serializable {}

  // Chat message
  public static class ChatMsg implements Serializable {
    public final String topic;   // "topic" of the conversation
    public final int n;          // the number of the reply in the current topic
    public final int senderId;   // the ID of the message sender
    public final int[] vc;       // vector clock

    public ChatMsg(String topic, int n, int senderId, int[] vc) {
      this.topic = topic;
      this.n = n;
      this.senderId = senderId;
      this.vc = new int[vc.length];
      for (int i=0; i<vc.length; i++)
        this.vc[i] = vc[i];
    }
  }

  // A message requesting to print the chat history
  public static class PrintHistoryMsg implements Serializable {}

  /* -- Actor constructor --------------------------------------------------- */
  public Chatter(int id, String topic) {
    this.id = id;
    this.myTopic = topic;
  }
  
  static public Props props(int id, String topic) { // used by the system to create actors
    return Props.create(Chatter.class, () -> new Chatter(id, topic));
  }

  /* -- Actor behaviour ----------------------------------------------------- */
  private void sendChatMsg(String topic, int n) {
    sendCount++;
    this.vc[this.id]++; //increment the vector clock element of the current actor
    ChatMsg m = new ChatMsg(topic, n, this.id, this.vc);
    System.out.printf("%02d: %s%02d\n", this.id, topic, n);
    multicast(m);
    appendToHistory(m); // append the sent message
  }

  private void multicast(Serializable m) { // our multicast implementation
    List<ActorRef> shuffledGroup = new ArrayList<>(group);
    Collections.shuffle(shuffledGroup);
    for (ActorRef p: shuffledGroup) {
      if (!p.equals(getSelf())) { // not sending to self
        p.tell(m, getSelf());
        try { Thread.sleep(rnd.nextInt(10)); } // --the sleep is just to simulate real application
        catch (InterruptedException e) { e.printStackTrace(); }
      }
    }
  }
  /*private void multicast(Serializable m) { // our multicast implementation
    for (ActorRef p: group) {
    if (!p.equals(getSelf())) // not sending to self
    p.tell(m, getSelf());
    }
    }*/

  // Here we define the mapping between the received message types
  // and our actor methods
  @Override
  public Receive createReceive() {
    return receiveBuilder()
      .match(JoinGroupMsg.class,    this::onJoinGroupMsg)
      .match(StartChatMsg.class,    this::onStartChatMsg)
      .match(ChatMsg.class,         this::onChatMsg)
      .match(PrintHistoryMsg.class, this::printHistory)
      .build();
  }

  private void onJoinGroupMsg(JoinGroupMsg msg) {
    this.group = msg.group;
    // create the vector clock
    this.vc = new int[this.group.size()];
    System.out.printf("%s: joining a group of %d peers with ID %02d\n",
        getSelf().path().name(), this.group.size(), this.id);
  }

  private void onStartChatMsg(StartChatMsg msg) {
    sendChatMsg(myTopic, 0); // start topic with message 0
  }

  private void onChatMsg(ChatMsg msg) {
	  if(canDeliver(msg)) { // if I can deliver		  
		  updateVC(msg); // update my own vector clock
		  deliver(msg);  // "deliver" the message to the simulated chat user
		  ChatMsg m = extractDeliverable();
		  if (m != null) {
			  onChatMsg(m);
		  }
	  }else { // I cannot deliver the message so put it on the buffer	
		  buffer.add(msg);
	  }
  }

  private ChatMsg extractDeliverable() {
	  Iterator<ChatMsg> I = buffer.iterator();
	  while(I.hasNext()) {
		  ChatMsg m = I.next();
		  if(canDeliver(m)) {
			  I.remove();
			  return m;
		  }
	  }
	  return null;
}

private void updateVC(ChatMsg msg) {
	  int[] vcS = msg.vc;
	  int[] vcR = this.vc;
	  for (int i=0; i<vcS.length; i++) {
		  vcR[i] = Math.max(vcR[i], vcS[i]);
	  }
}

private boolean canDeliver(ChatMsg msg) {
	  int[] vcS = msg.vc;
	  int[] vcR = this.vc;
	  int senderId = msg.senderId;
	  if(vcS[senderId] == vcR[senderId] + 1 && checkSecondCondition(senderId, vcS, vcR)) {
		  return true;
	  }
	  return false;
}

private boolean checkSecondCondition(int senderId, int[] vcS, int[] vcR) {
	
	for (int i=0; i<vcS.length; i++) {
		if (i!=senderId && vcS[i] > vcR[i]) {
			return false;		
		}
	}
	return true;
}

private void deliver(ChatMsg m) {
    // Our "chat application" appends all the received messages to the
    // chatHistory and replies if the topic of the message is interesting
    appendToHistory(m);

    if (myTopic != null && m.topic.equals(myTopic)  // the message is on my topic
        && sendCount < N_MESSAGES) // I still have something to say
    {
      // reply to the received message with an incremented value and the same topic
      sendChatMsg(m.topic, m.n+1);
    }
  }

  private void appendToHistory(ChatMsg m) {
    chatHistory.append(m.topic + m.n + " ");
  }

  private void printHistory(PrintHistoryMsg msg) {
    System.out.printf("%02d: %s\n", this.id, chatHistory);
    
    /*Iterator<ChatMsg> I = buffer.iterator();
    System.out.printf("buffer size: %d\n", buffer.size());
	while(I.hasNext()) {
		ChatMsg m = I.next();
		if (m != null) {
			System.out.printf("%d %s %d\n", m.senderId, m.topic, m.n);
		} else {
			System.out.print("buffer is null");
		}	
	}*/
  }
}

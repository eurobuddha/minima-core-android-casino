package com.eurobuddha.casino;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class OfferKeepAliveTest {
    static final String HOUSE="0xAA", ADDR="0xBB", COMMIT="0xCC";
    static Bet offer(String id,String token,long created) {
        Coin c=new Coin();c.coinid=id;c.tokenid=token;c.created=created;c.amount="0.123456789123456789";
        String[] fields={HOUSE,ADDR,COMMIT,"2","2",c.amount,"0","1500"};
        for(int i=0;i<fields.length;i++)c.state.put(i,fields[i]);
        return new Bet(c);
    }
    @Test public void renewalIsEarlyAndCurrencyIndependent() {
        for(String token:Arrays.asList("0x00",Currency.USD_TOKENID)) {
            Bet b=offer("0x01",token,100);
            assertFalse(OfferKeepAlive.due(b,599));assertTrue(OfferKeepAlive.due(b,600));
            assertFalse(OfferKeepAlive.due(b,99));assertFalse(OfferKeepAlive.due(offer("0x02",token,-1),10000));
            b.coin.state.put(6,"1");assertFalse(OfferKeepAlive.due(new Bet(b.coin),10000));
        }
    }
    @Test public void oneInputOneOutputPreservesAllTermsAndExactCollateral() {
        for(String token:Arrays.asList("0x00",Currency.USD_TOKENID)) {
            Bet b=offer("0x01",token,0);List<String> cmds=OfferKeepAlive.commands(b,"renew_test");
            assertEquals(1,cmds.stream().filter(s->s.startsWith("txninput ")).count());
            assertEquals(1,cmds.stream().filter(s->s.startsWith("txnoutput ")).count());
            assertTrue(cmds.contains("txnoutput id:renew_test amount:"+b.totalAmount+" address:"+CasinoContract.SCRIPT_ADDR+" tokenid:"+token+" storestate:true"));
            for(int p=0;p<8;p++)assertTrue(cmds.contains("txnstate id:renew_test port:"+p+" value:"+b.coin.stateAt(p)));
            assertTrue(cmds.contains("txnsign id:renew_test publickey:"+HOUSE));
            assertFalse(cmds.stream().anyMatch(s->s.contains("port:12")||s.startsWith("send ")||s.startsWith("txnpost ")));
        }
    }
    @Test public void maliciousOrUnderfundedOffersCannotReachTheBuilder() {
        Bet b=offer("0x01","0x00",0);b.coin.state.put(1,"[x];maths calculate:1+1;[x]");
        assertFalse(OfferKeepAlive.valid(new Bet(b.coin)));
        try {OfferKeepAlive.commands(new Bet(b.coin),"renew_test");fail();}catch(IllegalArgumentException expected){}
        b=offer("0x01","0x00",0);b.coin.amount="0.1";assertFalse(OfferKeepAlive.valid(new Bet(b.coin)));
        b=offer("0x01","0x00",0);b.coin.state.put(12,"0xDD");assertFalse(OfferKeepAlive.valid(new Bet(b.coin)));
        assertFalse(OfferKeepAlive.valid(offer("0x01","0xDD",0)));
    }
    static class FakeTxn extends CasinoTxn {
        final Set<String> cancelled=new HashSet<>();final List<String> maintained=new ArrayList<>(),cancelledCoins=new ArrayList<>();
        boolean fail;Result held;boolean hold;
        FakeTxn(){super(null,null,"","");}
        @Override public boolean cancelRequested(Bet b){return cancelled.contains(b.houseCommit);}
        @Override public void maintainOpen(Bet b,Result cb){maintained.add(b.coinid());if(cancelRequested(b))cancelledCoins.add(b.coinid());if(hold)held=cb;else if(fail)cb.onFailed("offline");else cb.onPosted("txpow");}
        @Override public void reveal(Bet b,Result cb){cb.onPosted("revealed");}
    }
    @Test public void sevenOffersRenewOnceAndTheirYoungSuccessorsStayIdle() {
        FakeTxn tx=new FakeTxn();AutoProcessor p=new AutoProcessor(tx);List<Bet> bets=new ArrayList<>();
        for(int i=0;i<7;i++)bets.add(offer(String.format("0x%02x",i),i<4?"0x00":Currency.USD_TOKENID,0));
        for(int block=500;block<=503;block++)p.process(bets,Collections.singleton(HOUSE),block,null);
        p.process(bets,Collections.singleton(HOUSE),503,null);
        assertEquals(7,tx.maintained.size());
        List<Bet> successors=new ArrayList<>();for(int i=0;i<7;i++)successors.add(offer(String.format("0x%02x",i+10),bets.get(i).tokenid(),504));
        p.process(successors,Collections.singleton(HOUSE),505,null);assertEquals(7,tx.maintained.size());
        p.process(successors,Collections.singleton("stranger"),1100,null);assertEquals(7,tx.maintained.size());
    }
    @Test public void cancellationFollowsTheCommitmentAcrossRenewalAndRestart() {
        FakeTxn tx=new FakeTxn();AutoProcessor p=new AutoProcessor(tx);Bet old=offer("0x01","0x00",0);
        p.process(Collections.singletonList(old),Collections.singleton(HOUSE),500,null);
        tx.cancelled.add(COMMIT); // user cancelled while the old renewal was already mining
        Bet replacement=offer("0x02","0x00",501);
        new AutoProcessor(tx).process(Collections.singletonList(replacement),Collections.singleton(HOUSE),502,null);
        assertEquals(Collections.singletonList("0x02"),tx.cancelledCoins);
        replacement.coin.state.put(6,"1");
        p.process(Collections.singletonList(new Bet(replacement.coin)),Collections.singleton(HOUSE),1000,null);
        assertEquals(1,tx.cancelledCoins.size()); // a taker winning the race never gets cancelled
        p.process(Collections.emptyList(),Collections.singleton(HOUSE),1001,null);assertEquals(2,tx.maintained.size());
    }
    @Test public void failedAndSlowRenewalsCannotFloodTheNode() {
        FakeTxn tx=new FakeTxn();tx.fail=true;AutoProcessor p=new AutoProcessor(tx);List<Bet> bets=Collections.singletonList(offer("0x01","0x00",0));
        p.process(bets,Collections.singleton(HOUSE),500,null);p.process(bets,Collections.singleton(HOUSE),501,null);assertEquals(1,tx.maintained.size());
        p.process(bets,Collections.singleton(HOUSE),504,null);assertEquals(2,tx.maintained.size());
        tx.hold=true;p.process(bets,Collections.singleton(HOUSE),508,null);p.process(bets,Collections.singleton(HOUSE),520,null);assertEquals(3,tx.maintained.size());
        tx.held.onPosted("done");p.process(Collections.emptyList(),Collections.singleton(HOUSE),521,null);
    }
}

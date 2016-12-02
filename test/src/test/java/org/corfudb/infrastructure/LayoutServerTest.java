package org.corfudb.infrastructure;


import lombok.extern.slf4j.Slf4j;

import org.assertj.core.api.Assertions;
import org.corfudb.protocols.wireprotocol.CorfuMsgType;
import org.corfudb.protocols.wireprotocol.CorfuPayloadMsg;
import org.corfudb.protocols.wireprotocol.LayoutBootstrapRequest;
import org.corfudb.protocols.wireprotocol.LayoutCommittedRequest;
import org.corfudb.protocols.wireprotocol.LayoutMsg;
import org.corfudb.protocols.wireprotocol.LayoutPrepareRequest;
import org.corfudb.protocols.wireprotocol.LayoutProposeRequest;
import org.corfudb.runtime.view.Layout;
import org.junit.Test;


import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.corfudb.infrastructure.LayoutServerAssertions.assertThat;

/**
 * Created by mwei on 12/14/15.
 */
@Slf4j
public class LayoutServerTest extends AbstractServerTest {

    @Override
    public LayoutServer getDefaultServer() {
        String serviceDir = getTempDir();
        return getDefaultServer(serviceDir);
    }


    /**
     * Verifies that a server that is not yet bootstrap does not respond with
     * a layout.
     */
    @Test
    public void nonBootstrappedServerNoLayout() {
        requestLayout(0);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_NOBOOTSTRAP_ERROR);
    }

    /**
     * Verifies that a server responds with a layout that the server was bootstrapped with.
     * There are no layout changes between bootstrap and layout request.
     */
    @Test
    public void bootstrapServerInstallsNewLayout() {
        Layout layout = TestLayoutBuilder.single(9000);
        bootstrapServer(layout);
        requestLayout(layout.getEpoch());
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_RESPONSE);
        Assertions.assertThat(((LayoutMsg) getLastMessage()).getLayout()).isEqualTo(layout);
    }

    /**
     * Verifies that a server cannot be bootstrapped multiple times.
     */
    @Test
    public void cannotBootstrapServerTwice() {
        Layout layout = TestLayoutBuilder.single(9000);
        bootstrapServer(layout);
        bootstrapServer(layout);
        Assertions.assertThat(getLastMessage().getMsgType())
                .isEqualTo(CorfuMsgType.LAYOUT_ALREADY_BOOTSTRAP_ERROR);
    }


    /**
     * Verifies that once a prepare with a rank has been accepted,
     * any subsequent prepares with lower ranks are rejected.
     * Note: This is in the scope of same epoch.
     */
    @Test
    public void prepareRejectsLowerRanks() {
        Layout layout = TestLayoutBuilder.single(9000);
        long epoch = layout.getEpoch();
        bootstrapServer(layout);
        sendPrepare(epoch, 100);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PREPARE_ACK_RESPONSE);
        sendPrepare(epoch, 10);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PREPARE_REJECT_ERROR);
    }

    /**
     * Verifies that once a prepare with a rank has been accepted,
     * any propose with a lower rank is rejected.
     * Note: This is in the scope of same epoch.
     */
    @Test
    public void proposeRejectsLowerRanks() {
        Layout layout = TestLayoutBuilder.single(9000);
        long epoch = layout.getEpoch();
        bootstrapServer(layout);
        sendPrepare(epoch, 100);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PREPARE_ACK_RESPONSE);
        sendPropose(epoch, 10, layout);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PROPOSE_REJECT_ERROR);
    }

    /**
     * Verifies that once a proposal has been accepted, the same proposal is not accepted again.
     * Note: This is in the scope of same epoch.
     */
    @Test
    public void proposeRejectsAlreadyProposed() {
        Layout layout = TestLayoutBuilder.single(9000);
        long epoch = layout.getEpoch();
        bootstrapServer(layout);
        sendPrepare(epoch, 10);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PREPARE_ACK_RESPONSE);
        sendPropose(epoch, 10, layout);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.ACK_RESPONSE);
        sendPropose(epoch, 10, layout);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PROPOSE_REJECT_ERROR);
    }

    /**
     * Verifies all phases set epoch, prepare, propose, commit.
     * Note: this is in the scope of a single epoch.
     */
    @Test
    public void commitReturnsAck() {
        Layout layout = TestLayoutBuilder.single(9000);
        bootstrapServer(layout);

        long newEpoch = layout.getEpoch() + 1;
        Layout newLayout = TestLayoutBuilder.single(9000);
        newLayout.setEpoch(newEpoch);

        // set epoch on servers
        setEpoch(newEpoch);

        sendPrepare(newEpoch, 100);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PREPARE_ACK_RESPONSE);

        sendPropose(newEpoch, 100, newLayout);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.ACK_RESPONSE);

        sendCommitted(newEpoch, newLayout);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.ACK_RESPONSE);
    }

    /**
     * Verifies that once set the epoch cannot regress.
     * Note: it does not verify that epoch is a dense monotonically increasing integer
     * sequence.
     */
    @Test
    public void checkServerEpochDoesNotRegress() {
        Layout layout = TestLayoutBuilder.single(9000);
        long epoch = layout.getEpoch();

        bootstrapServer(layout);

        setEpoch(2);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.ACK_RESPONSE);

        requestLayout(epoch);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_RESPONSE);
        Assertions.assertThat(getLastMessage().getEpoch()).isEqualTo(2);

        setEpoch(1);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.WRONG_EPOCH_ERROR);

    }

    /**
     * Verifies that a layout is persisted across server reboots.
     *
     * @throws Exception
     */
    @Test
    public void checkLayoutPersisted() throws Exception {
        //serviceDirectory from which all instances of corfu server are to be booted.
        String serviceDir = getTempDir();

        LayoutServer s1 = getDefaultServer(serviceDir);

        Layout layout = TestLayoutBuilder.single(9000);
        bootstrapServer(layout);

        Layout newLayout = TestLayoutBuilder.single(9000);
        long newEpoch = 100;
        newLayout.setEpoch(newEpoch);
        setEpoch(newEpoch);

        // Start the process of electing a new layout. But that layout will not take effect
        // till it is committed.
        sendPrepare(newEpoch, 1);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PREPARE_ACK_RESPONSE);

        sendPropose(newEpoch, 1, newLayout);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.ACK_RESPONSE);
        assertThat(s1).isInEpoch(newEpoch);
        assertThat(s1).isPhase1Rank(new Rank(1L, AbstractServerTest.testClientId));
        assertThat(s1).isPhase2Rank(new Rank(1L, AbstractServerTest.testClientId));
        s1.shutdown();

        LayoutServer s2 = getDefaultServer(serviceDir);
        this.router.reset();
        this.router.addServer(s2);

        assertThat(s2).isInEpoch(newEpoch);  // SLF: TODO: rebase conflict: new is 0, old was 100
        assertThat(s2).isPhase1Rank(new Rank(1L, AbstractServerTest.testClientId));
        assertThat(s2).isPhase2Rank(new Rank(1L, AbstractServerTest.testClientId));

        // request layout using the old epoch.
        requestLayout(0);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_RESPONSE);
        Assertions.assertThat(((LayoutMsg) getLastMessage()).getLayout().getEpoch()).isEqualTo(0);

        // request layout using the new epoch.
        requestLayout(newEpoch);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_RESPONSE);
        Assertions.assertThat(((LayoutMsg) getLastMessage()).getLayout().getEpoch()).isEqualTo(0);
    }

    /**
     * The test verifies that the data in accepted phase1 and phase2 messages
     * is persisted to disk and survives layout server restarts.
     *
     * @throws Exception
     */
    @Test
    public void checkPaxosPhasesPersisted() throws Exception {
        String serviceDir = getTempDir();
        LayoutServer s1 = getDefaultServer(serviceDir);

        Layout layout = TestLayoutBuilder.single(9000);
        bootstrapServer(layout);

        long newEpoch = layout.getEpoch() + 1;
        Layout newLayout = TestLayoutBuilder.single(9000);
        newLayout.setEpoch(newEpoch);

        setEpoch(newEpoch);

        // validate phase 1
        sendPrepare(newEpoch, 1);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PREPARE_ACK_RESPONSE);
        assertThat(s1).isPhase1Rank(new Rank(1L, AbstractServerTest.testClientId));
       //shutdown this instance of server
        s1.shutdown();
        //bring up a new instance of server with the previously persisted data
        LayoutServer s2 = getDefaultServer(serviceDir);

        assertThat(s2).isInEpoch(newEpoch);
        assertThat(s2).isPhase1Rank(new Rank(1L, AbstractServerTest.testClientId));

        // validate phase2 data persistence
        sendPropose(newEpoch, 1, newLayout);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.ACK_RESPONSE);
        //shutdown this instance of server
        s2.shutdown();

        //bring up a new instance of server with the previously persisted data
        LayoutServer s3 = getDefaultServer(serviceDir);

        assertThat(s3).isInEpoch(newEpoch);
        assertThat(s3).isPhase1Rank(new Rank(1L, AbstractServerTest.testClientId));
        assertThat(s3).isPhase2Rank(new Rank(1L, AbstractServerTest.testClientId));
        assertThat(s3).isProposedLayout(newLayout);

    }

    /**
     * Validates that the layout server accept or rejects incoming phase1 messages based on
     * the last persisted phase1 rank.
     *
     * @throws Exception
     */
    @Test
    public void checkMessagesValidatedAgainstPhase1PersistedData() throws Exception {
        String serviceDir = getTempDir();
        LayoutServer s1 = getDefaultServer(serviceDir);
        Layout layout = TestLayoutBuilder.single(9000);
        bootstrapServer(layout);

        long newEpoch = layout.getEpoch() + 1;
        Layout newLayout = TestLayoutBuilder.single(9000);
        newLayout.setEpoch(newEpoch);

        setEpoch(newEpoch);
        // validate phase 1
        sendPrepare(newEpoch, 100);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PREPARE_ACK_RESPONSE);
        assertThat(s1).isInEpoch(newEpoch);
        assertThat(s1).isPhase1Rank(new Rank(100L, AbstractServerTest.testClientId));

        s1.shutdown();
        // reboot
        LayoutServer s2 = getDefaultServer(serviceDir);
        assertThat(s2).isInEpoch(newEpoch);
        assertThat(s2).isPhase1Rank(new Rank(100L, AbstractServerTest.testClientId));

        //new LAYOUT_PREPARE message with a lower phase1 rank should be rejected
        sendPrepare(newEpoch, 99);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PREPARE_REJECT_ERROR);


        //new LAYOUT_PREPARE message with a higher phase1 rank should be accepted
        sendPrepare(newEpoch, 101);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PREPARE_ACK_RESPONSE);
    }

    /**
     * Validates that the layout server accept or rejects incoming phase2 messages based on
     * the last persisted phase1 and phase2 data.
     * If persisted phase1 rank does not match the LAYOUT_PROPOSE message then the server did not
     * take part in the prepare phase. It should reject this message.
     * If the persisted phase2 rank is the same as incoming message, it will be rejected as it is a
     * duplicate message.
     *
     * @throws Exception
     */
    @Test
    public void checkMessagesValidatedAgainstPhase2PersistedData() throws Exception {
        String serviceDir = getTempDir();
        LayoutServer s1 = getDefaultServer(serviceDir);
        Layout layout = TestLayoutBuilder.single(9000);
        bootstrapServer(layout);

        long newEpoch = layout.getEpoch() + 1;
        Layout newLayout = TestLayoutBuilder.single(9000);
        newLayout.setEpoch(newEpoch);

        setEpoch(newEpoch);
        assertThat(s1).isInEpoch(newEpoch);

        // validate phase 1
        sendPrepare(newEpoch, 100);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PREPARE_ACK_RESPONSE);
        assertThat(s1).isPhase1Rank(new Rank(100L, AbstractServerTest.testClientId));

        s1.shutdown();

        LayoutServer s2 = getDefaultServer(serviceDir);
        assertThat(s2).isInEpoch(newEpoch);
        assertThat(s2).isPhase1Rank(new Rank(100L, AbstractServerTest.testClientId));

        //new LAYOUT_PROPOSE message with a lower phase2 rank should be rejected
        sendPropose(newEpoch, 99, newLayout);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PROPOSE_REJECT_ERROR);


        //new LAYOUT_PROPOSE message with a rank that does not match LAYOUT_PREPARE should be rejected
        sendPropose(newEpoch, 101, newLayout);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PROPOSE_REJECT_ERROR);

        //new LAYOUT_PROPOSE message with same rank as phase1 should be accepted
        sendPropose(newEpoch, 100, newLayout);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.ACK_RESPONSE);
        assertThat(s2).isProposedLayout(newLayout);

        s2.shutdown();
        // data should survive the reboot.
        LayoutServer s3 = getDefaultServer(serviceDir);
        assertThat(s3).isInEpoch(newEpoch);
        assertThat(s3).isPhase1Rank(new Rank(100L, AbstractServerTest.testClientId));
        assertThat(s3).isProposedLayout(newLayout);
    }

    /**
     * Validates that the layout server accept or rejects incoming phase1 and phase2 messages from multiple
     * clients based on current state {Phease1Rank [rank, clientID], Phase2Rank [rank, clientID] }
     * If LayoutServer has accepted a phase1 message from a client , it can only accept a higher ranked phase1 message
     * from another client.
     * A phase2 message can only be accepted if the last accepted phase1 message is from the same client and has the
     * same rank.
     *
     * @throws Exception
     */
    @Test
    public void checkPhase1AndPhase2MessagesFromMultipleClients() throws Exception {
        String serviceDir = getTempDir();

        LayoutServer s1 = getDefaultServer(serviceDir);
        Layout layout = TestLayoutBuilder.single(9000);
        bootstrapServer(layout);

        long newEpoch = layout.getEpoch() + 1;
        Layout newLayout = TestLayoutBuilder.single(9000);
        newLayout.setEpoch(newEpoch);

        setEpoch(newEpoch);

        /* validate phase 1 */
        sendPrepare(newEpoch, 100);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PREPARE_ACK_RESPONSE);
        assertThat(s1).isPhase1Rank(new Rank(100L, AbstractServerTest.testClientId));

        // message from a different client with same rank should be rejected or accepted based on
        // whether the uuid is greater of smaller.
        sendPrepare(UUID.nameUUIDFromBytes("OTHER_CLIENT".getBytes()), newEpoch, 100);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PREPARE_REJECT_ERROR);

        sendPrepare(UUID.nameUUIDFromBytes("TEST_CLIENT_OTHER".getBytes()), newEpoch, 100);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PREPARE_REJECT_ERROR);

        // message from a different client but with a higher rank gets accepted
        sendPrepare(UUID.nameUUIDFromBytes("OTHER_CLIENT".getBytes()), newEpoch, 101);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PREPARE_ACK_RESPONSE);
        assertThat(s1).isPhase1Rank(new Rank(101L, UUID.nameUUIDFromBytes("OTHER_CLIENT".getBytes())));

        // testing behaviour after server restart
        s1.shutdown();
        LayoutServer s2 = getDefaultServer(serviceDir);
        assertThat(s2).isInEpoch(newEpoch);
        assertThat(s2).isPhase1Rank(new Rank(101L, UUID.nameUUIDFromBytes("OTHER_CLIENT".getBytes())));
        //duplicate message to be rejected
        sendPrepare(UUID.nameUUIDFromBytes("OTHER_CLIENT".getBytes()), newEpoch, 101);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PREPARE_REJECT_ERROR);

        /* validate phase 2 */

        //phase2 message from a different client than the one whose phase1 was last accepted is rejected
        sendPropose(newEpoch, 101, newLayout);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PROPOSE_REJECT_ERROR);

        // phase2 from same client with same rank as in phase1 gets accepted
        sendPropose(UUID.nameUUIDFromBytes("OTHER_CLIENT".getBytes()), newEpoch, 101, newLayout);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.ACK_RESPONSE);

        assertThat(s2).isInEpoch(newEpoch);
        assertThat(s2).isPhase1Rank(new Rank(101L, UUID.nameUUIDFromBytes("OTHER_CLIENT".getBytes())));
        assertThat(s2).isPhase2Rank(new Rank(101L, UUID.nameUUIDFromBytes("OTHER_CLIENT".getBytes())));
        assertThat(s2).isProposedLayout(newLayout);

        s2.shutdown();
    }

    @Test
    public void testReboot() throws Exception {
        String serviceDir = getTempDir();
        LayoutServer s1 = getDefaultServer(serviceDir);
        setServer(s1);

        Layout layout = TestLayoutBuilder.single(9000);
        layout.setEpoch(99);
        bootstrapServer(layout);

        // Reboot, then check that our epoch 100 layout is still there.
        s1.reboot();

        requestLayout(99);
        Assertions.assertThat(getLastMessage().getMsgType())
                .isEqualTo(CorfuMsgType.LAYOUT_RESPONSE);
        Assertions.assertThat(((LayoutMsg) getLastMessage()).getLayout().getEpoch()).isEqualTo(99);
        s1.shutdown();

        for (int i = 0; i < 16; i++) {
            LayoutServer s2 = getDefaultServer(serviceDir);
            setServer(s2);
            commitReturnsAck(s2, i, 100);
            s2.shutdown();
        }
    }

    // Same as commitReturnsAck() test, but we perhaps make a .reboot() call
    // between each step.

    private void commitReturnsAck(LayoutServer s1, Integer reboot, long baseEpoch) {

        if ((reboot & 1) > 0) {
            s1.reboot();
        }
        long newEpoch = baseEpoch + reboot;
        sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.SET_EPOCH, newEpoch));

        Layout layout = TestLayoutBuilder.single(9000);
        layout.setEpoch(newEpoch);

        sendPrepare(newEpoch, 100);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_PREPARE_ACK_RESPONSE);

        if ((reboot & 2) > 0) {
            log.debug("Rebooted server because reboot & 2 {}", reboot & 2);
            s1.reboot();
        }

        sendPropose(newEpoch, 100, layout);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.ACK_RESPONSE);

        if ((reboot & 4) > 0) {
            s1.reboot();
        }

        sendCommitted(newEpoch, layout);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.ACK_RESPONSE);

        if ((reboot & 8) > 0) {
            s1.reboot();
        }

        sendCommitted(newEpoch, layout);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.ACK_RESPONSE);

        requestLayout(newEpoch);
        Assertions.assertThat(getLastMessage().getMsgType()).isEqualTo(CorfuMsgType.LAYOUT_RESPONSE);
        Assertions.assertThat(((LayoutMsg) getLastMessage()).getLayout()).isEqualTo(layout);

    }

    private LayoutServer getDefaultServer(String serviceDir) {
        LayoutServer s1 = new LayoutServer(new ServerContextBuilder().setSingle(false).setMemory(false).setLogPath(serviceDir).setServerRouter(getRouter()).build());
        setServer(s1);
        return s1;
    }

    private void bootstrapServer(Layout l) {
        sendMessage(CorfuMsgType.LAYOUT_BOOTSTRAP.payloadMsg(new LayoutBootstrapRequest(l)));
    }

    private void requestLayout(long epoch) {
        sendMessage(CorfuMsgType.LAYOUT_REQUEST.payloadMsg(epoch));
    }

    private void setEpoch(long epoch) {
        sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.SET_EPOCH, epoch));
    }

    private void sendPrepare(long epoch, long rank) {
        sendMessage(CorfuMsgType.LAYOUT_PREPARE.payloadMsg(new LayoutPrepareRequest(epoch, rank)));
    }

    private void sendPropose(long epoch, long rank, Layout layout) {
        sendMessage(CorfuMsgType.LAYOUT_PROPOSE.payloadMsg(new LayoutProposeRequest(epoch, rank, layout)));
    }

    private void sendCommitted(long epoch, Layout layout) {
        sendMessage(CorfuMsgType.LAYOUT_COMMITTED.payloadMsg(new LayoutCommittedRequest(epoch, layout)));
    }

    private void sendPrepare(UUID clientId, long epoch, long rank) {
        sendMessage(clientId, CorfuMsgType.LAYOUT_PREPARE.payloadMsg(new LayoutPrepareRequest(epoch, rank)));
    }

    private void sendPropose(UUID clientId, long epoch, long rank, Layout layout) {
        sendMessage(clientId, CorfuMsgType.LAYOUT_PROPOSE.payloadMsg(new LayoutProposeRequest(epoch, rank, layout)));
    }

    private void sendCommitted(UUID clientId, long epoch, Layout layout) {
        sendMessage(clientId, CorfuMsgType.LAYOUT_COMMITTED.payloadMsg(new LayoutCommittedRequest(epoch, layout)));
    }
}

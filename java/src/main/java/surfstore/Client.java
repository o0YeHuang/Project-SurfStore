package surfstore;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.google.protobuf.ByteString;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import surfstore.SurfStoreBasic.Block;
import surfstore.SurfStoreBasic.Block.Builder;
import surfstore.SurfStoreBasic.Empty;
import surfstore.SurfStoreBasic.FileInfo;
import surfstore.SurfStoreBasic.WriteResult;
import surfstore.SurfStoreBasic.WriteResult.Result;
import surfstore.SurfStoreBasic.SimpleAnswer;



public final class Client {
 private static final Logger logger = Logger.getLogger(Client.class.getName());

    private final ManagedChannel metadataChannel;
    private final MetadataStoreGrpc.MetadataStoreBlockingStub metadataStub;
    private final ManagedChannel metadataChannel2;

    private final MetadataStoreGrpc.MetadataStoreBlockingStub metadataStub2;
    private final ManagedChannel metadataChannel3;
    private final MetadataStoreGrpc.MetadataStoreBlockingStub metadataStub3;

    private final ManagedChannel blockChannel;
    private final BlockStoreGrpc.BlockStoreBlockingStub blockStub;

    private final ConfigReader config;

    public Client(ConfigReader config) {
        this.metadataChannel = ManagedChannelBuilder.forAddress("127.0.0.1", config.getMetadataPort(1))
                .usePlaintext(true).build();
        this.metadataStub = MetadataStoreGrpc.newBlockingStub(metadataChannel);

        if (config.getNumMetadataServers() > 1) {
            this.metadataChannel2 = ManagedChannelBuilder.forAddress("127.0.0.1", config.getMetadataPort(2))
                    .usePlaintext(true).build();
            this.metadataStub2 = MetadataStoreGrpc.newBlockingStub(metadataChannel2);

            this.metadataChannel3 = ManagedChannelBuilder.forAddress("127.0.0.1", config.getMetadataPort(3))
                    .usePlaintext(true).build();
            this.metadataStub3 = MetadataStoreGrpc.newBlockingStub(metadataChannel3);
        } else {
            this.metadataChannel2 = null;
            this.metadataStub2 = null;
            this.metadataChannel3 = null;
            this.metadataStub3 = null;
        }

        this.blockChannel = ManagedChannelBuilder.forAddress("127.0.0.1", config.getBlockPort())
                .usePlaintext(true).build();
        this.blockStub = BlockStoreGrpc.newBlockingStub(blockChannel);

        this.config = config;
    }

    public void shutdown() throws InterruptedException {
        metadataChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        blockChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    private void ensure(boolean b) {
        if (b == false) {
            throw new RuntimeException("Assertion failed");
        }
    }

    private void testBlockServer() {
        blockStub.ping(Empty.newBuilder().build());
        logger.info("Testing the block server");

        Block b1 = stringToBlock("block_01");
        Block b2 = stringToBlock("block_02");
        Block b2a = stringToBlock("block_02");

        // 1. Blockstore reports that non-existent blocks are not present
        ensure(blockStub.hasBlock(b1).getAnswer() == false);
        ensure(blockStub.hasBlock(b2).getAnswer() == false);

        // 2a. Add a block and ensure it is now present
        blockStub.storeBlock(b1);
        ensure(blockStub.hasBlock(b1).getAnswer() == true);

        // 2b. Add a second block and ensure it is now present
        blockStub.storeBlock(b2);
        ensure(blockStub.hasBlock(b2).getAnswer() == true);

        // 2c. Ensure that a different block w/ same contents shows as present
        ensure(blockStub.hasBlock(b2a).getAnswer() == true);

        // 3a. Getting a block should return a block with the original hash and contents
        Block b1prime = blockStub.getBlock(b1);
        ensure(b1prime.getHash().equals(b1.getHash()));
        ensure(b1.getData().equals(b1prime.getData()));

        // 3b. Getting a second block should return a block with the original
        // hash and contents
        Block b2prime = blockStub.getBlock(b2);
        ensure(b2prime.getHash().equals(b2.getHash()));
        ensure(b2prime.getData().equals(b2.getData()));

        logger.info("Block server passed all the tests... yay!");
    }

    private void test_md_centralized_filenotfound() {

        metadataStub.ping(Empty.newBuilder().build());
        logger.info("Running test test_md_centralized_filenotfound");

        // test for a non-existant file
        FileInfo nonExistantFile = FileInfo.newBuilder().setFilename("notfound.txt").build();
        FileInfo nonExistantFileResult = metadataStub.readFile(nonExistantFile);
        ensure(nonExistantFileResult.getFilename().equals("notfound.txt"));
        ensure(nonExistantFileResult.getVersion() == 0);

        logger.info("test_md_centralized_filenotfound test passed... yay!");
    }

    private void test_md_centralized_missingblocks() {
        metadataStub.ping(Empty.newBuilder().build());
        logger.info("Running test test_md_centralized_missingblocks");

        // test for a file with a good version, but missing blocks
        Block cat_b0 = stringToBlock("cat_block0");
        Block cat_b1 = stringToBlock("cat_block1");
        Block cat_b2 = stringToBlock("cat_block2");

        ArrayList<String> cathashlist = new ArrayList<String>();
        cathashlist.add(cat_b0.getHash());
        cathashlist.add(cat_b1.getHash());
        cathashlist.add(cat_b2.getHash());

        surfstore.SurfStoreBasic.FileInfo.Builder catBuilder = FileInfo.newBuilder();
        catBuilder.setFilename("garfield.txt");
        catBuilder.setVersion(1);
        catBuilder.addAllBlocklist(cathashlist);
        FileInfo catreq = catBuilder.build();

        WriteResult catresult = metadataStub.modifyFile(catreq);
        ensure(catresult.getResult().equals(Result.MISSING_BLOCKS));
        ensure(catresult.getMissingBlocksCount() == 3);

        blockStub.storeBlock(cat_b0);
        catresult = metadataStub.modifyFile(catreq);
        ensure(catresult.getResult().equals(Result.MISSING_BLOCKS));
        ensure(catresult.getMissingBlocksCount() == 2);

        blockStub.storeBlock(cat_b1);
        catresult = metadataStub.modifyFile(catreq);
        ensure(catresult.getResult().equals(Result.MISSING_BLOCKS));
        ensure(catresult.getMissingBlocksCount() == 1);

        blockStub.storeBlock(cat_b2);
        catresult = metadataStub.modifyFile(catreq);
        ensure(catresult.getResult().equals(Result.OK));

        logger.info("test_md_centralized_missingblocks test passed... yay!");
    }

    private void test_md_version_tests() {
        metadataStub.ping(Empty.newBuilder().build());
        logger.info("Running test test_md_version_tests");

        /*
         * ver1: [b0,b1,b2]
         * ver2: [b0,b2,b3]
         * ver3: [b2,b3,b4]
         * ver4: [deleted]
         * ver5: [b4,b3,b2,b1]
         * ver6: [deleted]
         * ver7: [b4,b1]
         */

        Block cat_b0 = stringToBlock("cat_block0");
        Block cat_b1 = stringToBlock("cat_block1");
        Block cat_b2 = stringToBlock("cat_block2");
        Block cat_b3 = stringToBlock("cat_block3");
        Block cat_b4 = stringToBlock("cat_block4");
        blockStub.storeBlock(cat_b0);
        blockStub.storeBlock(cat_b1);
        blockStub.storeBlock(cat_b2);
        blockStub.storeBlock(cat_b3);
        blockStub.storeBlock(cat_b4);

        ArrayList<String> catver1 = new ArrayList<String>();
        catver1.add(cat_b0.getHash());
        catver1.add(cat_b1.getHash());
        catver1.add(cat_b2.getHash());

        ArrayList<String> catver2 = new ArrayList<String>();
        catver2.add(cat_b0.getHash());
        catver2.add(cat_b2.getHash());
        catver2.add(cat_b3.getHash());

        ArrayList<String> catver3 = new ArrayList<String>();
        catver3.add(cat_b2.getHash());
        catver3.add(cat_b3.getHash());
        catver3.add(cat_b4.getHash());

        ArrayList<String> catver5 = new ArrayList<String>();
        catver5.add(cat_b4.getHash());
        catver5.add(cat_b3.getHash());
        catver5.add(cat_b2.getHash());
        catver5.add(cat_b1.getHash());

        ArrayList<String> catver7 = new ArrayList<String>();
        catver7.add(cat_b4.getHash());
        catver7.add(cat_b1.getHash());

        // successful file creation (ver = 1)
        surfstore.SurfStoreBasic.FileInfo.Builder cat1builder = FileInfo.newBuilder();
        cat1builder.setFilename("heathcliff.txt");
        cat1builder.setVersion(1);
        cat1builder.addAllBlocklist(catver1);
        FileInfo cat1req = cat1builder.build();
        WriteResult cat1result = metadataStub.modifyFile(cat1req);
        ensure(cat1result.getResult().equals(Result.OK));
        FileInfo cat1readresult = metadataStub.readFile(cat1req);
        ensure(cat1readresult.getFilename().equals("heathcliff.txt"));
        ensure(cat1readresult.getVersion() == 1);
        //logger.info("Passed: successful file creation - ver = 1");


        // successful v1->v2
        surfstore.SurfStoreBasic.FileInfo.Builder cat2builder = FileInfo.newBuilder();
        cat2builder.setFilename("heathcliff.txt");
        cat2builder.setVersion(2);
        cat2builder.addAllBlocklist(catver2);
        FileInfo cat2req = cat2builder.build();
        WriteResult cat2result = metadataStub.modifyFile(cat2req);
        ensure(cat2result.getResult().equals(Result.OK));
        FileInfo cat2readresult = metadataStub.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff.txt"));
        ensure(cat2readresult.getVersion() == 2);
        //logger.info("Passed: successful v1->v2");


        // unsuccessful v2->v5
        surfstore.SurfStoreBasic.FileInfo.Builder cat5builder = FileInfo.newBuilder();
        cat5builder.setFilename("heathcliff.txt");
        cat5builder.setVersion(5);
        cat5builder.addAllBlocklist(catver5);
        FileInfo cat5req = cat5builder.build();
        WriteResult cat5result = metadataStub.modifyFile(cat5req);
        ensure(cat5result.getResult().equals(Result.OLD_VERSION));
        cat2readresult = metadataStub.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff.txt"));
        ensure(cat2readresult.getVersion() == 2);
        //logger.info("Passed: unsuccessful v2->v5");

        // unsuccessful v2->v1
        cat1result = metadataStub.modifyFile(cat1req);
        ensure(cat1result.getResult().equals(Result.OLD_VERSION));
        cat2readresult = metadataStub.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff.txt"));
        ensure(cat2readresult.getVersion() == 2);
        //logger.info("Passed: unsuccessful v2->v1");


        // successful v2->v3
        surfstore.SurfStoreBasic.FileInfo.Builder cat3builder = FileInfo.newBuilder();
        cat3builder.setFilename("heathcliff.txt");
        cat3builder.setVersion(3);
        cat3builder.addAllBlocklist(catver3);
        FileInfo cat3req = cat3builder.build();
        WriteResult cat3result = metadataStub.modifyFile(cat3req);
        ensure(cat3result.getResult().equals(Result.OK));
        cat2readresult = metadataStub.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff.txt"));
        ensure(cat2readresult.getVersion() == 3);
        //logger.info("Passed: successful v2->v3");


        // unsuccessful v3->v7 (delete)
        surfstore.SurfStoreBasic.FileInfo.Builder cat7builder = FileInfo.newBuilder();
        cat7builder.setFilename("heathcliff.txt");
        cat7builder.setVersion(7);
        FileInfo cat7req = cat7builder.build();
        WriteResult cat7result = metadataStub.deleteFile(cat7req);
        ensure(cat7result.getResult().equals(Result.OLD_VERSION));
        cat2readresult = metadataStub.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff.txt"));
        ensure(cat2readresult.getVersion() == 3);
        //logger.info("Passed: unsuccessful v3->v7 (delete)");


        // successful v3->v4 (delete)
        surfstore.SurfStoreBasic.FileInfo.Builder cat4builder = FileInfo.newBuilder();
        cat4builder.setFilename("heathcliff.txt");
        cat4builder.setVersion(4);
        FileInfo cat4req = cat4builder.build();
        WriteResult cat4result = metadataStub.deleteFile(cat4req);
        ensure(cat4result.getResult().equals(Result.OK));
        cat2readresult = metadataStub.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff.txt"));
        ensure(cat2readresult.getVersion() == 4);
        //logger.info("Passed: successful v3->v4 (delete)");


        logger.info("test_md_version_tests test passed... yay!");
    }

    private void test_md_crashtest() {
        logger.info("Running test test_md_crashtest");

        metadataStub.ping(Empty.newBuilder().build());
        metadataStub2.ping(Empty.newBuilder().build());
        metadataStub3.ping(Empty.newBuilder().build());

        // test that we can crash and recover servers
        SimpleAnswer ans = metadataStub2.isCrashed(Empty.newBuilder().build());
        ensure(ans.getAnswer() == false);
        metadataStub2.crash(Empty.newBuilder().build());
        ans = metadataStub2.isCrashed(Empty.newBuilder().build());
        ensure(ans.getAnswer() == true);
        metadataStub2.restore(Empty.newBuilder().build());
        ans = metadataStub2.isCrashed(Empty.newBuilder().build());
        ensure(ans.getAnswer() == false);

        ans = metadataStub3.isCrashed(Empty.newBuilder().build());
        ensure(ans.getAnswer() == false);
        metadataStub3.crash(Empty.newBuilder().build());
        ans = metadataStub3.isCrashed(Empty.newBuilder().build());
        ensure(ans.getAnswer() == true);
        metadataStub3.restore(Empty.newBuilder().build());
        ans = metadataStub3.isCrashed(Empty.newBuilder().build());
        ensure(ans.getAnswer() == false);

        logger.info("test_md_crashtest test passed... yay!");
    }

    private void test_md_updatewhilecrashed() {
        logger.info("Running test test_md_updatewhilecrashed");

        metadataStub.ping(Empty.newBuilder().build());
        metadataStub2.ping(Empty.newBuilder().build());
        metadataStub3.ping(Empty.newBuilder().build());

        Block cat_b0 = stringToBlock("cat_block0");
        Block cat_b1 = stringToBlock("cat_block1");
        Block cat_b2 = stringToBlock("cat_block2");
        blockStub.storeBlock(cat_b0);
        blockStub.storeBlock(cat_b1);
        blockStub.storeBlock(cat_b2);

        ArrayList<String> catver1 = new ArrayList<String>();
        catver1.add(cat_b0.getHash());
        catver1.add(cat_b1.getHash());
        catver1.add(cat_b2.getHash());

        ArrayList<String> catver2 = new ArrayList<String>();
        catver2.add(cat_b0.getHash());
        catver2.add(cat_b2.getHash());
        catver2.add(cat_b1.getHash());

        ArrayList<String> catver3 = new ArrayList<String>();
        catver3.add(cat_b2.getHash());
        catver3.add(cat_b1.getHash());
        catver3.add(cat_b0.getHash());

        // successful file creation (ver = 1)
        surfstore.SurfStoreBasic.FileInfo.Builder cat1builder = FileInfo.newBuilder();
        cat1builder.setFilename("meowth.txt");
        cat1builder.setVersion(1);
        cat1builder.addAllBlocklist(catver1);
        FileInfo cat1req = cat1builder.build();
        WriteResult cat1result = metadataStub.modifyFile(cat1req);
        ensure(cat1result.getResult().equals(Result.OK));

        // verify ver=1
        FileInfo cat1readresult = metadataStub.readFile(cat1req);
        ensure(cat1readresult.getFilename().equals("meowth.txt"));
        ensure(cat1readresult.getVersion() == 1);
        cat1readresult = metadataStub2.readFile(cat1req);
        ensure(cat1readresult.getFilename().equals("meowth.txt"));
        ensure(cat1readresult.getVersion() == 1);
        cat1readresult = metadataStub3.readFile(cat1req);
        ensure(cat1readresult.getFilename().equals("meowth.txt"));
        ensure(cat1readresult.getVersion() == 1);

        // crash follower 2
        metadataStub2.crash(Empty.newBuilder().build());

        // update the file to v2
        surfstore.SurfStoreBasic.FileInfo.Builder cat2builder = FileInfo.newBuilder();
        cat2builder.setFilename("meowth.txt");
        cat2builder.setVersion(2);
        cat2builder.addAllBlocklist(catver2);
        FileInfo cat2req = cat2builder.build();
        WriteResult cat2result = metadataStub.modifyFile(cat2req);
        ensure(cat2result.getResult().equals(Result.OK));

        // verify ver=2 for 'up' followers, ver=1 for 'crashed' follower
        FileInfo cat2readresult = metadataStub.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("meowth.txt"));
        ensure(cat2readresult.getVersion() == 2);
        cat2readresult = metadataStub2.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("meowth.txt"));
        ensure(cat2readresult.getVersion() == 1);
        cat2readresult = metadataStub3.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("meowth.txt"));
        ensure(cat2readresult.getVersion() == 2);

        // restore follower 2
        metadataStub2.restore(Empty.newBuilder().build());
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // verify ver=2 for all followers
        cat2readresult = metadataStub.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("meowth.txt"));
        ensure(cat2readresult.getVersion() == 2);
        cat2readresult = metadataStub2.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("meowth.txt"));

        ensure(cat2readresult.getVersion() == 2);
        cat2readresult = metadataStub3.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("meowth.txt"));
        ensure(cat2readresult.getVersion() == 2);

        logger.info("test_md_updatewhilecrashed test passed... yay!");
    }

    // Creating our own tests
    private void test_md_callmodifyordeleteonfollowers() {
        logger.info("Running test test_md_callmodifyordeleteonfollowers");

        metadataStub.ping(Empty.newBuilder().build());
        metadataStub2.ping(Empty.newBuilder().build());
        metadataStub3.ping(Empty.newBuilder().build());

        Block cat_b0 = stringToBlock("cat_block0_extra");
        Block cat_b1 = stringToBlock("cat_block1_extra");
        Block cat_b2 = stringToBlock("cat_block2_extra");
        blockStub.storeBlock(cat_b0);
        blockStub.storeBlock(cat_b1);
        blockStub.storeBlock(cat_b2);

        ArrayList<String> catver1 = new ArrayList<String>();
        catver1.add(cat_b0.getHash());
        catver1.add(cat_b1.getHash());
        catver1.add(cat_b2.getHash());


        // Calling modifyFile on followers
        surfstore.SurfStoreBasic.FileInfo.Builder cat1builder = FileInfo.newBuilder();
        cat1builder.setFilename("meowthExtra.txt");
        cat1builder.setVersion(1);
        cat1builder.addAllBlocklist(catver1);
        FileInfo cat1req = cat1builder.build();

        // ModifyFile Tests
        // Calling modifyFile on follower 1
        WriteResult cat2result = metadataStub2.modifyFile(cat1req);
        ensure(cat2result.getResult().equals(Result.NOT_LEADER));
        // Verify that the file has not been modified
        FileInfo cat2readresult = metadataStub2.readFile(cat1req);
        ensure(cat2readresult.getVersion() == 0);

        // Calling modifyFile on follower 2
        WriteResult cat3result = metadataStub3.modifyFile(cat1req);
        ensure(cat3result.getResult().equals(Result.NOT_LEADER));
        // Verify that the file has not been modified
        FileInfo cat3readresult = metadataStub3.readFile(cat1req);
        ensure(cat3readresult.getVersion() == 0);

        // successful file creation (ver = 1)
        WriteResult cat1result = metadataStub.modifyFile(cat1req);
        ensure(cat1result.getResult().equals(Result.OK));
        FileInfo cat1readresult =  metadataStub.readFile(cat1req);
        ensure(cat1readresult.getVersion() == 1);

        // DeleteFile Tests
        // Calling deleteFile on follower 1
        WriteResult cat2deleteresult = metadataStub2.deleteFile(cat1req);
        ensure(cat2deleteresult.getResult().equals(Result.NOT_LEADER));
        // Verify that the file has not been delete
        FileInfo cat2deletereadresult = metadataStub2.readFile(cat1req);
        ensure(cat2deletereadresult.getFilename().equals("meowthExtra.txt"));
        ensure(cat2deletereadresult.getVersion() == 1);

        // Calling deleteFile on follower 2
        WriteResult cat3deleteresult = metadataStub3.deleteFile(cat1req);
        ensure(cat3deleteresult.getResult().equals(Result.NOT_LEADER));
        // Verify that the file has not been modified
        FileInfo cat3deletereadresult = metadataStub3.readFile(cat1req);
        ensure(cat3deletereadresult.getFilename().equals("meowthExtra.txt"));
        ensure(cat3deletereadresult.getVersion() == 1);

        // successful file deletion (ver = 2)
        surfstore.SurfStoreBasic.FileInfo.Builder cat1builder_delete = FileInfo.newBuilder();
        cat1builder_delete.setFilename("meowthExtra.txt");
        cat1builder_delete.setVersion(2);
        FileInfo cat1req_delete = cat1builder_delete.build();
        WriteResult cat1deleteresult = metadataStub.deleteFile(cat1req_delete);
        ensure(cat1deleteresult.getResult().equals(Result.OK));
        FileInfo cat3deletereadresult_extra = metadataStub3.readFile(cat1req);
        ensure(cat3deletereadresult_extra.getFilename().equals("meowthExtra.txt"));
        ensure(cat3deletereadresult_extra.getVersion() == 2);

        logger.info("test_md_callmodifyordeleteonfollowers test passed... yay!");
    }

    private void test_md_version_tests_for_followers() {

        logger.info("Running test test_md_version_tests_for_followers");

        metadataStub.ping(Empty.newBuilder().build());
        metadataStub2.ping(Empty.newBuilder().build());
        metadataStub3.ping(Empty.newBuilder().build());

        /*
         * ver1: [b0,b1,b2]
         * ver2: [b0,b2,b3]
         * ver3: [b2,b3,b4]
         * ver4: [deleted]
         * ver5: [b4,b3,b2,b1]
         * ver6: [deleted]
         * ver7: [b4,b1]
         */

        Block cat_b0 = stringToBlock("cat_block0");
        Block cat_b1 = stringToBlock("cat_block1");
        Block cat_b2 = stringToBlock("cat_block2");
        Block cat_b3 = stringToBlock("cat_block3");
        Block cat_b4 = stringToBlock("cat_block4");
        blockStub.storeBlock(cat_b0);
        blockStub.storeBlock(cat_b1);
        blockStub.storeBlock(cat_b2);
        blockStub.storeBlock(cat_b3);
        blockStub.storeBlock(cat_b4);

        ArrayList<String> catver1 = new ArrayList<String>();
        catver1.add(cat_b0.getHash());
        catver1.add(cat_b1.getHash());
        catver1.add(cat_b2.getHash());

        ArrayList<String> catver2 = new ArrayList<String>();
        catver2.add(cat_b0.getHash());
        catver2.add(cat_b2.getHash());
        catver2.add(cat_b3.getHash());

        ArrayList<String> catver3 = new ArrayList<String>();
        catver3.add(cat_b2.getHash());
        catver3.add(cat_b3.getHash());
        catver3.add(cat_b4.getHash());

        ArrayList<String> catver5 = new ArrayList<String>();
        catver5.add(cat_b4.getHash());
        catver5.add(cat_b3.getHash());
        catver5.add(cat_b2.getHash());
        catver5.add(cat_b1.getHash());

        ArrayList<String> catver7 = new ArrayList<String>();
        catver7.add(cat_b4.getHash());
        catver7.add(cat_b1.getHash());

        // successful file creation (ver = 1)
        surfstore.SurfStoreBasic.FileInfo.Builder cat1builder = FileInfo.newBuilder();
        cat1builder.setFilename("heathcliff1.txt");
        cat1builder.setVersion(1);
        cat1builder.addAllBlocklist(catver1);
        FileInfo cat1req = cat1builder.build();
        WriteResult cat1result = metadataStub.modifyFile(cat1req);
        ensure(cat1result.getResult().equals(Result.OK));
        FileInfo cat1readresult = metadataStub.readFile(cat1req);
        ensure(cat1readresult.getFilename().equals("heathcliff1.txt"));
        ensure(cat1readresult.getVersion() == 1);
        // Verify version for followes
        cat1readresult = metadataStub2.readFile(cat1req);
        ensure(cat1readresult.getFilename().equals("heathcliff1.txt"));
        ensure(cat1readresult.getVersion() == 1);
        cat1readresult = metadataStub3.readFile(cat1req);
        ensure(cat1readresult.getFilename().equals("heathcliff1.txt"));
        ensure(cat1readresult.getVersion() == 1);


        // Crash follower 1
        metadataStub2.crash(Empty.newBuilder().build());
        SimpleAnswer ans = metadataStub2.isCrashed(Empty.newBuilder().build());
        ensure(ans.getAnswer() == true);

        // successful v1->v2
        surfstore.SurfStoreBasic.FileInfo.Builder cat2builder = FileInfo.newBuilder();
        cat2builder.setFilename("heathcliff1.txt");
        cat2builder.setVersion(2);
        cat2builder.addAllBlocklist(catver2);
        FileInfo cat2req = cat2builder.build();
        WriteResult cat2result = metadataStub.modifyFile(cat2req);
        ensure(cat2result.getResult().equals(Result.OK));
        FileInfo cat2readresult = metadataStub.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff1.txt"));
        ensure(cat2readresult.getVersion() == 2);
        // Verify version for followes
        cat2readresult = metadataStub2.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff1.txt"));
        ensure(cat2readresult.getVersion() == 1);
        cat2readresult = metadataStub3.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff1.txt"));
        ensure(cat2readresult.getVersion() == 2);


        // unsuccessful v2->v5
        surfstore.SurfStoreBasic.FileInfo.Builder cat5builder = FileInfo.newBuilder();
        cat5builder.setFilename("heathcliff1.txt");
        cat5builder.setVersion(5);
        cat5builder.addAllBlocklist(catver5);
        FileInfo cat5req = cat5builder.build();
        WriteResult cat5result = metadataStub.modifyFile(cat5req);
        ensure(cat5result.getResult().equals(Result.OLD_VERSION));
        cat2readresult = metadataStub.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff1.txt"));
        ensure(cat2readresult.getVersion() == 2);
        // Verify version for followes
        cat2readresult = metadataStub2.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff1.txt"));
        ensure(cat2readresult.getVersion() == 1);
        cat2readresult = metadataStub3.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff1.txt"));
        ensure(cat2readresult.getVersion() == 2);

        // unsuccessful v2->v1
        cat1result = metadataStub.modifyFile(cat1req);
        ensure(cat1result.getResult().equals(Result.OLD_VERSION));
        cat2readresult = metadataStub.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff1.txt"));
        ensure(cat2readresult.getVersion() == 2);
        // Verify version for followes
        cat2readresult = metadataStub2.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff1.txt"));
        ensure(cat2readresult.getVersion() == 1);
        cat2readresult = metadataStub3.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff1.txt"));
        ensure(cat2readresult.getVersion() == 2);


        // successful v2->v3
        surfstore.SurfStoreBasic.FileInfo.Builder cat3builder = FileInfo.newBuilder();
        cat3builder.setFilename("heathcliff1.txt");
        cat3builder.setVersion(3);
        cat3builder.addAllBlocklist(catver3);
        FileInfo cat3req = cat3builder.build();
        WriteResult cat3result = metadataStub.modifyFile(cat3req);
        ensure(cat3result.getResult().equals(Result.OK));
        cat2readresult = metadataStub.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff1.txt"));
        ensure(cat2readresult.getVersion() == 3);
        // Verify version for followes
        cat2readresult = metadataStub2.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff1.txt"));
        ensure(cat2readresult.getVersion() == 1);
        FileInfo cat3readresult = metadataStub3.readFile(cat2req);
        ensure(cat3readresult.getFilename().equals("heathcliff1.txt"));
        ensure(cat3readresult.getVersion() == 3);


        // unsuccessful v3->v7 (delete)
        surfstore.SurfStoreBasic.FileInfo.Builder cat7builder = FileInfo.newBuilder();
        cat7builder.setFilename("heathcliff1.txt");
        cat7builder.setVersion(7);
        FileInfo cat7req = cat7builder.build();
        WriteResult cat7result = metadataStub.deleteFile(cat7req);
        ensure(cat7result.getResult().equals(Result.OLD_VERSION));
        cat2readresult = metadataStub.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff1.txt"));
        ensure(cat2readresult.getVersion() == 3);
        // Verify version for followes
        cat2readresult = metadataStub2.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff1.txt"));
        ensure(cat2readresult.getVersion() == 1);
        cat2readresult = metadataStub3.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff1.txt"));
        ensure(cat2readresult.getVersion() == 3);

        // Restore follower 1
        metadataStub2.restore(Empty.newBuilder().build());
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // successful v3->v4 (delete)
        surfstore.SurfStoreBasic.FileInfo.Builder cat4builder = FileInfo.newBuilder();
        cat4builder.setFilename("heathcliff1.txt");
        cat4builder.setVersion(4);
        FileInfo cat4req = cat4builder.build();
        WriteResult cat4result = metadataStub.deleteFile(cat4req);
        ensure(cat4result.getResult().equals(Result.OK));
        cat2readresult = metadataStub.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff1.txt"));
        ensure(cat2readresult.getVersion() == 4);
        // Verify version for followes
        cat2readresult = metadataStub2.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff1.txt"));
        ensure(cat2readresult.getVersion() == 4);
        cat2readresult = metadataStub3.readFile(cat2req);
        ensure(cat2readresult.getFilename().equals("heathcliff1.txt"));
        ensure(cat2readresult.getVersion() == 4);


        logger.info("test_md_version_tests_for_followers test passed... yay!");
    }

    private static Namespace parseArgs(String[] args) {
        ArgumentParser parser = ArgumentParsers.newFor("Client").build()
                .description("Client for SurfStore");
        parser.addArgument("config_file").type(String.class)
                .help("Path to configuration file");

        Namespace res = null;
        try {
            res = parser.parseArgs(args);
        } catch (ArgumentParserException e){
            parser.handleError(e);
        }
        return res;
    }

    public static void main(String[] args) throws Exception {
        Namespace c_args = parseArgs(args);
        if (c_args == null){
            throw new RuntimeException("Argument parsing failed");
        }

        File configf = new File(c_args.getString("config_file"));
        ConfigReader config = new ConfigReader(configf);

        Client client = new Client(config);

        try {
            client.testBlockServer();
            client.test_md_centralized_filenotfound();
            client.test_md_centralized_missingblocks();
            client.test_md_version_tests();

            if (config.getNumMetadataServers() > 1) {
                client.test_md_crashtest();
                client.test_md_updatewhilecrashed();
                // Adding our own tests
                client.test_md_callmodifyordeleteonfollowers();
                client.test_md_version_tests_for_followers();
            }
        } finally {
            client.shutdown();
        }
    }

    private static Block stringToBlock(String s) {
        Builder builder = Block.newBuilder();

        try {
            builder.setData(ByteString.copyFrom(s, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        builder.setHash(sha256(s));

        return builder.build();
}

    public static String sha256(String s) {

        MessageDigest digest = null;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      e.printStackTrace();
      System.exit(2);
    }
        byte[] hash = digest.digest(s.getBytes(StandardCharsets.UTF_8));
        String encoded = Base64.getEncoder().encodeToString(hash);

        return encoded;
  }

}

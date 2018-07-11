#!/usr/bin/env python
import argparse
import os.path
import sys

import thread
import grpc
import hashlib
import base64
import SurfStoreBasic_pb2
import SurfStoreBasic_pb2_grpc

from config_reader import SurfStoreConfigReader

_BLOCK_SIZE = 4 * 1024

def sha256(s):
    m = hashlib.sha256()
    m.update(str.encode(s))
    return base64.b64encode(m.digest())

def parse_args():
    parser = argparse.ArgumentParser(description="SurfStore client")
    parser.add_argument("config_file", type=str,
                        help="Path to configuration file")
    return parser.parse_args()


def get_metadata_stub(config):
    channel = grpc.insecure_channel('localhost:%d' % config.metadata_ports[1])
    stub = SurfStoreBasic_pb2_grpc.MetadataStoreStub(channel)
    return stub


def get_block_stub(config):
    channel = grpc.insecure_channel('localhost:%d' % config.block_port)
    stub = SurfStoreBasic_pb2_grpc.BlockStoreStub(channel)
    return stub

def stringToBlock(s):
    print(s)
    builder = SurfStoreBasic_pb2.Block()
    try:
        builder.data = s.encode()
    except:
        print ("Encoding issues")
        raise SystemExit

    builder.hash = sha256(s)
    return builder

def blockToString(block):
    return block.data.decode()

def create_file(fn, metadata_stub, block_stub):
    # Read the file first
    fileinfo = metadata_stub.ReadFile(SurfStoreBasic_pb2.FileInfo(filename = fn))
    print("ReadFile rpc call returned successfully\n")

    # Read and parse the local file
    blocklist = []
    hashlist = []
    with open(fn) as f:
        chunck = f.read(_BLOCK_SIZE)
        blk = stringToBlock(chunck)
        # add the block to the blocklist
        blocklist.append(blk)
        # add the hash to the hashlist
        hashlist.append(blk.hash)
    # update the file version
    fileinfo.version += 1
    # assign the hashlist to FileInfo
    for hashVal in hashlist:
        fileinfo.blocklist.append(hashVal)
    # fileinfo.blocklist= hashlist
    # close the file
    f.close()

    # Calling ModifyFile RPC
    writeRes = metadata_stub.ModifyFile(fileinfo)
    print("ModifyFile rpc call returned successfully\n")

    # Check if result in old version
    while writeRes.result == SurfStoreBasic_pb2.WriteResult.OLD_VERSION:
        print("Write-write conflict occurs here, about to update the version number\n")
        fileinfo = metadata_stub.ReadFile(SurfStoreBasic_pb2.FileInfo(filename = fn))
        # update the file version
        fileinfo.version += 1
        # assign the hashlist to FileInfo
        fileinfo.blocklist = hashlist
        # Calling ModifyFile RPC
        writeRes = metadata_stub.ModifyFile(fileinfo)

    # Check if missing blocks
    if writeRes.result == SurfStoreBasic_pb2.WriteResult.MISSING_BLOCKS:
        print("Storing each missing block to BlockStore now\n")
        # Store each missing blocks into blockstore
        for hashes in writeRes.missing_blocks:
             block_stub.StoreBlock(blocklist[hashlist.index(hashes)])
        writeRes = metadata_stub.ModifyFile(fileinfo)

    # Signal file written
    if writeRes.result == SurfStoreBasic_pb2.WriteResult.OK:
        print("File has been written to the store successfully\n")
    return None

def read_file(fn, metadata_stub, block_stub):
    # Read the file first
    fileinfo = metadata_stub.ReadFile(SurfStoreBasic_pb2.FileInfo(filename = fn))
    print("ReadFile rpc call returned successfully\n")
    print(fileinfo.version)

    # Check if the file exist in Store
    if fileinfo.version == 0:
        print("File " + fn + " does not exist in SurfStore")
    else:
        f = open(fn+"1","w+")
        # Download the blocks from BlockStore
        print(fileinfo.blocklist)
        for hashes in fileinfo.blocklist:
            blk = block_stub.GetBlock(SurfStoreBasic_pb2.Block(hash = hashes))
            print(blk)
            print("GetBlock rpc call returned successfully\n")
            f.write(blockToString(blk))
        # Create and write the file
        f.close()
        print("File has been downloaded and created\n")


def change_file(fn, metadata_stub, block_stub):
    # change the file by add a string to it
    f = open(fn, "a+")
    f.append("changed")
    f.close()

    # Read the file first
    fileinfo = metadata_stub.ReadFile(SurfStoreBasic_pb2.FileInfo(filename = fn))
    print("ReadFile rpc call returned successfully\n")

    # Check if the file exist in Store
    if fileinfo.version == 0:
        print("File " + fn + " does not exist in SurfStore")
    else:
        # Read and parse the local file
        blocklist = []
        hashlist = []
        with open(fn) as f:
            chunck = f.readlines(_BLOCK_SIZE)
            blk = stringToBlock(chunck)
            # add the block to the blocklist
            blocklist.append(blk)
            # add the hash to the hashlist
            hashlist.append(blk.hash)
        # update the file version
        fileinfo.version += 1
        # assign the hashlist to FileInfo
        fileinfo.blocklist= hashlist
        # close the file
        f.close()

        # Calling ModifyFile RPC
        writeRes = metadata_stub.ModifyFile(fileinfo)
        print("ModifyFile rpc call returned successfully\n")

        # Check if result in old version
        while writeRes.result == SurfStoreBasic_pb2.WriteResult.OLD_VERSION:
            print("Write-write conflict occurs here, about to update the version number\n")
            fileinfo = metadata_stub.ReadFile(SurfStoreBasic_pb2.FileInfo(filename = fn))
            # update the file version
            fileinfo.version += 1
            # assign the hashlist to FileInfo
            fileinfo.blocklist = hashlist
            # Calling ModifyFile RPC
            writeRes = metadata_stub.ModifyFile(fileinfo)

        # Check if missing blocks
        if writeRes.result == SurfStoreBasic_pb2.WriteResult.MISSING_BLOCKS:
            print("Storing each missing block to BlockStore now\n")
            # Store each missing blocks into blockstore
            for hashes in writeRes.missing_blocks:
                 block_stub.StoreBlock(blocklist[hashlist.index(hashes)])
            writeRes = metadata_stub.ModifyFile(fileinfo)

        # Signal file written
        if writeRes.result == SurfStoreBasic_pb2.WriteResult.OK:
            print("File has been written to the store successfully\n")

        return None

def delete_file(fn, metadata_stub, block_stub):
    # Read the file first
    fileinfo = metadata_stub.ReadFile(SurfStoreBasic_pb2.FileInfo(filename = fn))
    print("ReadFile rpc call returned successfully\n")

    # Check if the file exist in Store
    if fileinfo.version == 0:
        print("File " + fn + " does not exist in SurfStore")

    else:
        # Calling DeleteFile RPC
        fileinfo.version += 1
        writeRes = metadata_stub.DeleteFile(fileinfo)
        print("DeleteFile rpc call returned successfully\n")

    if writeRes.result == SurfStoreBasic_pb2.WriteResult.NOT_LEADER:
        print("The metadata_stub server been called is not the leader\n")
    elif writeRes.result == SurfStoreBasic_pb2.WriteResult.OK:
        print("Delete the file successfully\n")

    return None

def run(config):
    metadata_stub = get_metadata_stub(config)
    block_stub = get_block_stub(config)

    metadata_stub.Ping(SurfStoreBasic_pb2.Empty())
    print("Successfully pinged the Metadata server")

    block_stub.Ping(SurfStoreBasic_pb2.Empty())
    print("Successfully pinged the Blockstore server")

    # Implement your client here

    # Starting the client and waiting for user inputs
    # while True:

    # Create the file first
    create_file(filename, metadata_stub, block_stub)

    print("Starting two threads to test synchronization")
    try:
       thread.start_new_thread( print_time, ("Thread-1", 2, ) )
       thread.start_new_thread( print_time, ("Thread-2", 4, ) )
    except:
       print("Error: unable to start thread")

        # # reading in the operation the user wants to perform
        # operation = raw_input("Enter the command (create/read/change/delete/exit):")
        #
        # # handling different operations
        # if operation not in {"create", "read", "change", "delete", "exit"}:
        #     # handling incorrect operation inputted
        #     print("Please enter only (create/read/change/delete/exit):\n")
        #
        # else:
        #     # reading in the filename
        #     filename = raw_input("Enter the filename:")
        #     # if os.path.isfile(filename):
        #
        #     # handling create file
        #     if operation == "create":
        #         create_file(filename, metadata_stub, block_stub)
        #
        #     # handling read file
        #     elif operation == "read":
        #         read_file(filename, metadata_stub, block_stub)
        #
        #     # handling change file
        #     elif operation == "change":
        #         change_file(filename, metadata_stub, block_stub)
        #
        #     # handling delete file
        #     elif operation == "delete":
        #         delete_file(filename, metadata_stub, block_stub)
        #
        #     # handling on exit
        #     elif operation == "exit":
        #         break
            # go to next iteration when file not exist locally
            # else:
            #     print(filename + " is not a file")
            #     continue

    # return when exit
    return None

if __name__ == "__main__":
    args = parse_args()
    config = SurfStoreConfigReader(args.config_file)

    run(config)

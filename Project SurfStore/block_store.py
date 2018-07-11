#!/usr/bin/env python

""" SurfStore BlockStore Server 

The BlockStore Server exists as a singular process/instance. 

The Metadata Servers is responsible for the storing/mapping of hashes to blocks
"""

import argparse
import time
import threading
from concurrent import futures

import grpc

import SurfStoreBasic_pb2
import SurfStoreBasic_pb2_grpc

from config_reader import SurfStoreConfigReader

_ONE_DAY_IN_SECONDS = 60 * 60 * 24


class BlockStore(SurfStoreBasic_pb2_grpc.BlockStoreServicer):
    def __init__(self):
        super(BlockStore, self).__init__()
        self.myBlockStore = {}

    def Ping(self, request, context):
        """ Ping RPC Function

        Checks to see if a server is alive, by having a respond with an Empty RPC Message object

        Args:
            request (Empty): Empty Object

        Return:
            SimpleAnswer: SimpleAnswer RPC Message
        """
        return SurfStoreBasic_pb2.Empty()

    def StoreBlock(self, request, context):
        """ StoreBlock RPC Function

        Stores a block in myBlockStore dictionary, referenced by its hash

        Args:
            request (Block): Block RPC Message, contains a hash and the data

        Return:
            Empty: Empty RPC Message
        """
        lock = threading.Lock()
        lock.acquire()
        try:
            self.myBlockStore[request.hash] = request.data.decode()
        finally:
            lock.release()
        return SurfStoreBasic_pb2.Empty()

    def GetBlock(self, request, context):
        """ GetBlock RPC Function

        Returns a block from the myBlockStore dictionary, referenced by its hash

        Args:
            request (Block): Block RPC Message, contains a hash for which the data needs to be returned for

        Return:
            Block: BLock RPC Message containing data, and hash
        """
        builder = SurfStoreBasic_pb2.Block()
        try:
            data = self.myBlockStore[request.hash]
            builder.data = data.encode()
            builder.hash = request.hash
        except:
            print ("No mapping found for hash", request.hash)
        return builder        

    def HasBlock(self, request, context):
        """ Hash RPC Function

        Returns a block identified by a hash exists in myBlockStore

        Args:
            request (Block): Block RPC Message, contains a hash for which we're checking if it exists or not

        Return:
            SimpleAnswer: True if exists, false otherwise
        """
        builder = SurfStoreBasic_pb2.SimpleAnswer()
        try:
            if request.hash in self.myBlockStore.keys():
                builder.answer = True
            else:
                builder.answer = False
        except:
            print ("No mapping found for hash", request.hash)
        return builder

def parse_args():
    """ Helper Function to Parse command-line arguments

    Args:
        None

    Return:
        None
    """
    parser = argparse.ArgumentParser(description="BlockStore server for SurfStore")
    parser.add_argument("config_file", type=str,
                        help="Path to configuration file")
    parser.add_argument("-t", "--threads", type=int, default=10,
                        help="Maximum number of concurrent threads")
    return parser.parse_args()


def serve(args, config):
    """ Helper Function to start the BlockStore Server instance

    Args:
        args (obj): command line arguments object, made by ArgumentParser
        config (obj): configuration object produced by ConfigReader from config text files

    Return:
        None
    """
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=args.threads))
    SurfStoreBasic_pb2_grpc.add_BlockStoreServicer_to_server(BlockStore(), server)
    server.add_insecure_port("127.0.0.1:%d" % config.block_port)
    server.start()
    print("Server started on 127.0.0.1:%d" % config.block_port)
    try:
        while True:
            time.sleep(_ONE_DAY_IN_SECONDS)
    except KeyboardInterrupt:
        server.stop(0)


if __name__ == "__main__":
    args = parse_args()
    config = SurfStoreConfigReader(args.config_file)
    serve(args, config)

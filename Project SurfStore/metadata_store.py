#!/usr/bin/env python

""" SurfStore Metadata Server 

The Metadata Server runs three processes/instances (indivdually started), where one process/instance is 
a leader, and the remaining two are followers.

The Metadata Servers are responsible for the handling of all commands from the client, as well as keeping
track of the relation to filename, versions, and blocks/blocklist.
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

global global_config

class MetadataStore(SurfStoreBasic_pb2_grpc.MetadataStoreServicer):
    """MetadataStore Class

    Args:
        config (obj): Configuration object read and parsed from config file
        isLeader (bool): Boolean representing if this instance is a leader or not

    Attributes:
        myMetaDataStore (dict): Holds the state of files, versions, and their blocklists
        config (obj): Configuration object needed to produce stubs to other follower Metadata Servers
        isLeader (bool): Boolean representing if this Metadata Server instance is a leader or not
        isCrashed (bool): Boolean representing if this Metadata Server instance is crashed
        logs (dict): Holds all the logs of each command (except for Read commands)
        follower_stubs (list): List containing stubs to connect to follower servers
        follower1_missedLogs (list): List containing missed log entries for follower #1 server when it was crashed
        follower2_missedLogs (list): List containing missed log entries for follower #2 server when it was crashed
        currentCommandIndex (int): Current Index of command/log
    """
    def __init__(self, config, isLeader):
        super(MetadataStore, self).__init__()
        self.myMetaDataStore = {}
        self.config = config
        self.isLeader = isLeader
        self.isCrashed = False
        self.logs = {}
        self.follower_stubs = self.get_follower_stubs()
        self.follower1_missedLogs = []
        self.follower2_missedLogs = []
        if self.isLeader == True:
            self.currentCommandIndex = 0
            t = threading.Thread(name="logSync" , target=self.syncMissingLogs)
            t.start()

    def get_block_stub(self):
        """ Helper Function to Produce Block Stub

        Used to get stub for the BlockStore Server

        Args:
            None

        Return:
            None
        """
        channel = grpc.insecure_channel('localhost:%d' % self.config.block_port)
        stub = SurfStoreBasic_pb2_grpc.BlockStoreStub(channel)
        return stub

    def get_follower_stubs(self):
        """ Helper Function to Produce Follower Metadata Server Stub

        Used to get stubs for the Follower Metadata Servers

        Args:
            None

        Return:
            None
        """
        follower_stubs = []
        for i in self.config.metadata_ports.keys():
            if i != self.config.num_leaders:
                channel = grpc.insecure_channel('localhost:%d' % self.config.metadata_ports[i])
                stub = SurfStoreBasic_pb2_grpc.MetadataStoreStub(channel)
                follower_stubs.append(stub)
        return follower_stubs

    def addEntryToLog(self, log):
        """ Adds a command entry to the log

        Args:
            log (obj): Log object to add to logs dictionary

        Returns:
            None.
        """
        self.logs[log.index] = log

    def syncMissingLogs(self):
        """ Helper Missing Log Sync Function 

        This function is called on a seperate thread. Every 500ms it attempts to send any missing logs
        to both followers

        Args:
            None

        Return:
            None
        """
        while True:
            builder1 = SurfStoreBasic_pb2.LogEntries()
            builder2 = SurfStoreBasic_pb2.LogEntries()
            if len(self.follower1_missedLogs) > 0:
                for missingLog in self.follower1_missedLogs:
                    builder1.log_entries.extend([missingLog])
                resp1 = self.follower_stubs[0].AppendEntries(builder1)
                print("Attempting to sync missed logs to follower #1\n")
                if resp1.server_crashed == False:
                    self.follower1_missedLogs = []
            if len(self.follower2_missedLogs) > 0:
                for missingLog in self.follower2_missedLogs:
                    builder2.log_entries.extend([missingLog])
                print("Attempting to sync missed logs to follower #2\n")
                resp2 = self.follower_stubs[0].AppendEntries(builder1)
                if resp2.server_crashed == False:
                    self.follower2_missedLogs = []
            time.sleep(0.5)

    def createLogFromCommand(self, request, command):
        """ Helper Log Creation Function

        Creates a log from a FileInfo object and adds this log to our collection

        Args:
            request (FileInfo): FileInfo object for a command
            command (int): Command Enum referencing type of command

        Return:
            None
        """
        log_builder = SurfStoreBasic_pb2.LogEntry()
        log_builder.file_info.CopyFrom(request)
        log_builder.index = self.currentCommandIndex 
        log_builder.command = command
        self.addEntryToLog(log_builder)

    def updateMetadataStoreFromLog(self, log):
        """ Helper Function to Update myMetadataStore from a Log Entry

        Used by AppendEntries RPC Call to updata a follower's myMetadataStore from a missing log entry

        Args:
            log (LogEntry): Missing Log Entry

        Return:
            None
        """
        if log.command != 0:
            self.myMetaDataStore[log.file_info.filename] = (log.file_info.version, log.file_info.blocklist)

    def TwoPhaseCommit(self):
        """ Helper Function to carry out 2-Phase Commit with follower server

        Allows for a command to be processed if the majority (qty: 1) of the follower servers are online, if not, then 
        this Two-Phase Commit process will block untul the majority are onlne

        Args:
            None

        Return:
            None
        """
        f1_response = False
        f2_response = False
        while (f1_response == False) and (f2_response == False):
            response1_future = self.follower_stubs[0].Prepare.future(SurfStoreBasic_pb2.Empty())
            response1 = response1_future.result()
            if response1:
                if response1.server_crashed == False:
                    f1_response = response1.answer
            response2_future = self.follower_stubs[1].Prepare.future(SurfStoreBasic_pb2.Empty())
            response2 = response2_future.result()
            if response2:
                if response2.server_crashed == False:
                    f2_response = response2.answer
        resp1 = self.follower_stubs[0].Commit(self.logs[self.currentCommandIndex])
        if resp1.server_crashed == True:
            self.follower1_missedLogs.append(self.logs[self.currentCommandIndex])
        resp2 = self.follower_stubs[1].Commit(self.logs[self.currentCommandIndex])
        if resp1.server_crashed == True:
            self.follower1_missedLogs.append(self.logs[self.currentCommandIndex])
        self.currentCommandIndex += 1

    def Ping(self, request, context):
        """ Ping RPC Function

        Checks to see if a server is alive, by having a respond with an Empty RPC Message object

        Args:
            request (Empty): Empty Object

        Return:
            SimpleAnswer: SimpleAnswer RPC Message
        """
        return SurfStoreBasic_pb2.Empty()

    def AppendEntries(self, request, context):
        """ AppendEntries RPC Function

        Used by Leader MetadataStore Server to sync missing logs when a follower was down
        
        Args:
            request (LogEntry): Missing Log Entry

        Return:
            LogEntries: LogEntries RPC Message
        """
        builder = SurfStoreBasic_pb2.SimpleAnswer()
        if self.isCrashed == True:
            builder.server_crashed = True
        else:
            for missedLog in request.log_entries:
                print("Appending Log:\nFilename: %s\nVersion: %d\nCommand: %d\n" %(missedLog.file_info.filename, missedLog.file_info.version, missedLog.command))
                self.updateMetadataStoreFromLog(missedLog)
            builder.answer = True
        return builder

    def Prepare(self, request, context):
        """ Prepare RPC Function

        RPC needed for part one of 2-Phase Commit
        
        Args:
            request (Empty): Empty Object

        Return:
            SimpleAnswer: SimpleAnswer RPC Message
        """
        builder = SurfStoreBasic_pb2.SimpleAnswer()
        if self.isCrashed == False:
            builder.answer = True
            builder.server_crashed = False
        else:
            builder.server_crashed = True
        return builder

    def Commit(self, request, context):
        """ Commit RPC Function

        RPC needed for part two of 2-Phase Commit
        
        Args:
            request (LogEntry): LogEntry object that will be converted to a command after this call succeeds

        Return:
            SimpleAnswer: SimpleAnswer RPC Message
        """
        builder = SurfStoreBasic_pb2.SimpleAnswer()
        if self.isCrashed == False:
            builder.answer = True
            builder.server_crashed = False
            print("Committing:\nFilename: %s\nVersion: %d\nCommand: %d\n" %(request.file_info.filename, request.file_info.version, request.command))
            if request.command != 0:
                self.myMetaDataStore[request.file_info.filename] = (request.file_info.version, request.file_info.blocklist)
                self.addEntryToLog(request)
        else:
            builder.server_crashed = True
        return builder

    def ReadFile(self, request, context):
        """ ReadFile RPC Function

        ReadFile Command 
        
        Args:
            request (FileInfo): Contains filename for which to return version and blocklist for

        Return:
            FileInfo: Returns a FileInfo object with version and blocklist appropriately set
        """
        builder = SurfStoreBasic_pb2.FileInfo()
        if request.filename in self.myMetaDataStore.keys():
            print ("Mapping found for: %s\n" % request.filename)
            (version, blocklist) = self.myMetaDataStore[request.filename]
            builder.filename = request.filename
            builder.version = self.myMetaDataStore[request.filename][0]
            for hashVal in self.myMetaDataStore[request.filename][1]:
                builder.blocklist.append(hashVal) 
            if self.isLeader == True:
                self.createLogFromCommand(request, 0)
                resp1 = self.follower_stubs[0].Commit(self.logs[self.currentCommandIndex])
                if resp1.server_crashed == True:
                    self.follower1_missedLogs.append(self.logs[self.currentCommandIndex])
                resp2 = self.follower_stubs[1].Commit(self.logs[self.currentCommandIndex])
                if resp1.server_crashed == True:
                    self.follower1_missedLogs.append(self.logs[self.currentCommandIndex])
                self.currentCommandIndex += 1
        else:
            print ("No mapping found for: %s" % request.filename)
            self.myMetaDataStore[request.filename] = (0, [])
            builder.filename = request.filename
            builder.version = 0
        return builder

    def ModifyFile(self, request, context):
        """ ModifyFile RPC Function

        ModifyFile Command 
        
        Args:
            request (FileInfo): Contains filename, with updated version and blocklist, to update myMetadataStore with

        Return:
            WriteResult: WriteResult RPC Message that indicates success or failure of this operation
        """
        builder = SurfStoreBasic_pb2.WriteResult()
        lock = threading.Lock()
        lock.acquire()
        try:
            if self.isLeader == True:
                block_stub = self.get_block_stub()
                missing_blocklist = []
                missing_blocks = False
                for hashVal in request.blocklist:
                    response = block_stub.HasBlock(SurfStoreBasic_pb2.Block(hash=hashVal))
                    if response.answer == False:
                        builder.missing_blocks.append(hashVal)
                        missing_blocks = True
                if missing_blocks == False:
                    if request.version == 1:
                        if request.filename not in self.myMetaDataStore.keys():
                            self.myMetaDataStore[request.filename] = (request.version, request.blocklist)
                            builder.current_version = request.version
                            self.createLogFromCommand(request, 1)
                            self.TwoPhaseCommit()
                        else:
                            if self.myMetaDataStore[request.filename][0] + 1 == request.version:
                                self.myMetaDataStore[request.filename] = (request.version, request.blocklist)
                                builder.current_version = request.version
                                builder.result = 0
                                self.createLogFromCommand(request, 1)
                                self.TwoPhaseCommit()
                            else:
                                builder.current_version = self.myMetaDataStore[request.filename][0]
                                builder.result = 1
                    elif request.version == (self.myMetaDataStore[request.filename][0] + 1):
                        self.myMetaDataStore[request.filename] = (request.version, request.blocklist)
                        builder.current_version = request.version
                        builder.result = 0
                        self.createLogFromCommand(request, 1)
                        self.TwoPhaseCommit()
                    else:
                        builder.current_version = self.myMetaDataStore[request.filename][0]
                        builder.result = 1
                else:
                    builder.result = 2
            else:
                builder.result = 3
        finally:
            lock.release()
        return builder

    def DeleteFile(self, request, context):
        """ DeleteFile RPC Function

        ModifyFile Command 
        
        Args:
            request (FileInfo): Contains filename to delete blocklist for in myMetadataStore, granted correct versions was supplied

        Return:
            WriteResult: WriteResult RPC Message that indicates success or failure of this operation
        """
        builder = SurfStoreBasic_pb2.WriteResult()
        lock = threading.Lock()
        lock.acquire()
        try:
            if self.isLeader == True:
                if request.filename in self.myMetaDataStore.keys():
                    if request.version == (self.myMetaDataStore[request.filename][0] + 1):
                        self.myMetaDataStore[request.filename] = (request.version, ["0"])
                        builder.current_version = request.version
                        builder.result = 0
                        self.createLogFromCommand(request, 2)
                        self.TwoPhaseCommit()
                    else:
                        builder.current_version = self.myMetaDataStore[request.filename][0]
                        builder.result = 1
                else:
                    print ("No mapping found for filename", request.filename)
                return builder
            else:
                builder.result = 3
        finally:
            lock.release()
        return builder

    def IsLeader(self, request, context):
        """ IsLeader RPC Function

        RPC Function used to determine if a Metadata Server is a leader or not
        
        Args:
            request (Empty): Empty Object

        Return:
            SimpleAnswer: True if leader, false otherwise
        """
        builder = SurfStoreBasic_pb2.SimpleAnswer()
        if self.isCrashed != True:
            if self.isLeader == True:
                builder.answer = True
            else:
                builder.answer = 0
            builder.isCrashed = False
        else: 
            builder.isCrashed = True
        return builder

    def Crash(self, request, context):
        """ Crash RPC Function

        RPC Function used to emulate a "crashed" server state (only Follower Metadata Server will crash)

        Args:
            request (Empty): Empty RPC Message

        Return:
             Empty: Empty RPC Message
        """
        self.isCrashed = True
        print("Now in crashed state...\n")
        return SurfStoreBasic_pb2.Empty()

    def Restore(self, request, context):
        """ Restore RPC Function

        RPC Function used to restore a follower Metadata Server from its emulated "crashed" state

        Args:
            request (Empty): Empty RPC Message

        Return:
             Empty: Empty RPC Message
        """
        print("Restoring from crashed state...\n")
        self.isCrashed = False
        return SurfStoreBasic_pb2.Empty()

    def IsCrashed(self, request, context):
        """ IsCrashed RPC Function

        RPC Function used to determine if a Metadata Server is in an emulated "crashed" state or not
        
        Args:
            request (Empty): Empty Object

        Return:
            SimpleAnswer: True if crashed, false otherwise
        """
        builder = SurfStoreBasic_pb2.SimpleAnswer()
        if self.isCrashed == True:
            builder.answer = True
            builder.server_crashed = True
        else:
            builder.answer = False
            builder.server_crashed = False
        return builder




def parse_args():
    """ Helper Function to Parse command-line arguments

    Args:
        None

    Return:
        None
    """
    parser = argparse.ArgumentParser(description="MetadataStore server for SurfStore")
    parser.add_argument("config_file", type=str,
                        help="Path to configuration file")
    parser.add_argument("-n", "--number", type=int, default=1,
                        help="Set which number this server is")
    parser.add_argument("-t", "--threads", type=int, default=10,
                        help="Maximum number of concurrent threads")
    return parser.parse_args()

def serve(args, config):
    """ Helper Function to start the Metadata Server instance

    Args:
        args (obj): command line arguments object, made by ArgumentParser
        config (obj): configuration object produced by ConfigReader from config text files

    Return:
        None
    """
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=args.threads))
    if args.number == config.num_leaders:
        SurfStoreBasic_pb2_grpc.add_MetadataStoreServicer_to_server(MetadataStore(config, True), server)
    else:
        SurfStoreBasic_pb2_grpc.add_MetadataStoreServicer_to_server(MetadataStore(config, False), server)
    server.add_insecure_port("127.0.0.1:%d" % config.metadata_ports[args.number])
    server.start()
    print("Server #%d started on 127.0.0.1:%d" % (args.number, config.metadata_ports[args.number]))
    try:
        while True:
            time.sleep(_ONE_DAY_IN_SECONDS)
    except KeyboardInterrupt:
        server.stop(0)

if __name__ == "__main__":
    args = parse_args()
    config = SurfStoreConfigReader(args.config_file)

    if args.number > config.num_metadata_servers:
        raise RuntimeError("metadata%d not defined in config file" % args.number)

    serve(args, config)

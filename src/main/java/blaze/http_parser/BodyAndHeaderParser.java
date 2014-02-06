package blaze.http_parser;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import blaze.http_parser.BaseExceptions.BadRequest;
import blaze.http_parser.BaseExceptions.InvalidState;

/**
 * @author Bryce Anderson
 *         Created on 2/4/14
 */
public abstract class BodyAndHeaderParser extends ParserBase {

    private enum HeaderState {
        START,
        HEADER_IN_NAME,
        HEADER_SPACE,
        HEADER_IN_VALUE,
        END
    }

    private enum ChunkState {
        START,
        CHUNK_SIZE,
        CHUNK_PARAMS,
        CHUNK,
        CHUNK_LF,
        CHUNK_TRAILERS,
        END
    }

    public enum EndOfContent { UNKNOWN_CONTENT,
        NO_CONTENT,
        CONTENT_LENGTH,
        CHUNKED_CONTENT,
        SELF_DEFINING_CONTENT,
        EOF_CONTENT,
    }


    private final int headerSizeLimit;
    private final int maxChunkSize;

    private HeaderState _hstate = HeaderState.START;

    private long _contentLength;
    private long _contentPosition;

    private ChunkState _chunkState;
    private int _chunkLength;
    private int _chunkPosition;

    private String _headerName;
    private EndOfContent _endOfContent;

    /* --------------------------------------------------------------------- */

    protected static byte[] HTTP10Bytes  = "HTTP/1.0".getBytes(StandardCharsets.US_ASCII);
    protected static byte[] HTTP11Bytes  = "HTTP/1.1".getBytes(StandardCharsets.US_ASCII);

    protected static byte[] HTTPS10Bytes = "HTTPS/1.0".getBytes(StandardCharsets.US_ASCII);
    protected static byte[] HTTPS11Bytes = "HTTPS/1.1".getBytes(StandardCharsets.US_ASCII);

    private static ByteBuffer emptyBuffer = ByteBuffer.allocate(0);

    /* Constructor --------------------------------------------------------- */

    protected BodyAndHeaderParser(int initialBufferSize, int headerSizeLimit, int maxChunkSize) {
        super(initialBufferSize);
        this.headerSizeLimit = headerSizeLimit;
        this.maxChunkSize = maxChunkSize;
    }

    /**
     * This is the method called by parser when a HTTP Header name and value is found
     * @param name The name of the header
     * @param value The value of the header
     * @return True if the parser should return to its caller
     */
    public abstract boolean headerComplete(String name, String value) throws BaseExceptions.BadRequest;

    /** determines if a body may follow the headers */
    public abstract boolean mayHaveBody();

    /* Status methods ---------------------------------------------------- */

    public final boolean headersComplete() {
        return _hstate == HeaderState.END;
    }

    public final boolean contentComplete() {
        return _endOfContent == EndOfContent.EOF_CONTENT ||
               _endOfContent == EndOfContent.NO_CONTENT;
    }

    public final boolean isChunked() {
        return _endOfContent == EndOfContent.CHUNKED_CONTENT;
    }

    public final boolean inChunkedHeaders() {
        return _chunkState == ChunkState.CHUNK_TRAILERS;
    }

    public final boolean definedContentLength() {
        return _endOfContent == EndOfContent.CONTENT_LENGTH;
    }

    public final EndOfContent getContentType() {
        return _endOfContent;
    }

    /* ----------------------------------------------------------------- */

    @Override
    void reset() {
        super.reset();

        _hstate = HeaderState.START;
        _chunkState = ChunkState.START;

        _endOfContent = EndOfContent.UNKNOWN_CONTENT;

        _contentLength = 0;
        _contentPosition = 0;
        _chunkLength = 0;
        _chunkPosition = 0;
    }

    public void shutdownParser() {
        _hstate = HeaderState.END;
        _chunkState = ChunkState.END;
        _endOfContent = EndOfContent.EOF_CONTENT;
    }

    protected final boolean parseHeaders(ByteBuffer in) throws BaseExceptions.BadRequest, BaseExceptions.InvalidState {

        headerLoop: while (true) {
            byte ch;
            switch (_hstate) {
                case START:
                    _hstate = HeaderState.HEADER_IN_NAME;
                    resetLimit(headerSizeLimit);

                case HEADER_IN_NAME:
                    for(ch = next(in); ch != ':' && ch != HttpTokens.LF; ch = next(in)) {
                        if (ch == 0) return false;
                        putByte(ch);
                    }

                    // Must be done with headers
                    if (bufferPosition() == 0) {

                        _hstate = HeaderState.END;

                        // Finished with the whole request
                        if (_chunkState == ChunkState.CHUNK_TRAILERS) shutdownParser();

                            // TODO: perhaps we should test against if it is GET, OPTION, or HEAD.
                        else if ((_endOfContent == EndOfContent.UNKNOWN_CONTENT &&
                                !mayHaveBody())) shutdownParser();

                        // Done parsing headers
                        return true;
                    }

                    if (ch == HttpTokens.LF) {  // Valueless header
                        String name = getString();
                        clearBuffer();

                        if (headerComplete(name, "")) {
                            return true;
                        }

                        continue headerLoop;    // Still parsing Header name
                    }

                    _headerName = getString();
                    clearBuffer();
                    _hstate = HeaderState.HEADER_SPACE;

                case HEADER_SPACE:
                    for(ch = next(in); ch == HttpTokens.SPACE || ch == HttpTokens.TAB; ch = next(in));

                    if (ch == 0) return false;

                    if (ch == HttpTokens.LF) {
                        shutdownParser();
                        throw new BadRequest("Missing value for header " + _headerName);
                    }

                    putByte(ch);
                    _hstate = HeaderState.HEADER_IN_VALUE;

                case HEADER_IN_VALUE:
                    for(ch = next(in); ch != HttpTokens.LF; ch = next(in)) {
                        if (ch == 0) return false;
                        putByte(ch);
                    }

                    String value;
                    try { value = getTrimmedString(); }
                    catch (BaseExceptions.BadRequest e) {
                        shutdownParser();
                        throw new BadRequest(e.msg());
                    }
                    clearBuffer();

                    // If we are not parsing trailer headers, look for some that are of interest to the request
                    if (_chunkState != ChunkState.CHUNK_TRAILERS) {

                        // Check for submitContent type if its still not determined
                        if (_endOfContent == EndOfContent.UNKNOWN_CONTENT) {
                            if (_headerName.equalsIgnoreCase("Transfer-Encoding")) {
                                if (!value.equalsIgnoreCase("chunked")) {
                                    shutdownParser();
                                    throw new BadRequest("Unknown Transfer-Encoding: " + value);
                                }

                                _endOfContent = EndOfContent.CHUNKED_CONTENT;
                            }
                            else if (_headerName.equalsIgnoreCase("Content-Length")) {
                                try {
                                    _contentLength = Long.parseLong(value);
                                }
                                catch (NumberFormatException t) {
                                    shutdownParser();
                                    throw new BadRequest("Invalid Content-Length: '" + value + "'\n");
                                }

                                _endOfContent = _contentLength <= 0 ?
                                        EndOfContent.NO_CONTENT: EndOfContent.CONTENT_LENGTH;
                            }
                        }
                    }

                    // Send off the header and see if we wish to continue
                    try {
                        if (headerComplete(_headerName, value)) {
                            return true;
                        }
                    } finally {
                        _hstate = HeaderState.HEADER_IN_NAME;
                    }

                    break;

                case END:
                    shutdownParser();
                    throw new InvalidState("Header parser reached invalid position.");
            }   // Switch
        }   // while loop
    }

    protected final ByteBuffer parseContent(ByteBuffer in) throws BaseExceptions.ParserException {
        switch (_endOfContent) {
            case UNKNOWN_CONTENT:
                // Need Content-Length or Transfer-Encoding to signal a body for GET
                // rfc2616 Sec 4.4 for more info
                // What about custom verbs which may have a body?
                // We could also CONSIDER doing a BAD Request here.

                _endOfContent = EndOfContent.SELF_DEFINING_CONTENT;
                return parseContent(in);

            case CONTENT_LENGTH:
                return nonChunkedContent(in);

            case CHUNKED_CONTENT:
                return chunkedContent(in);

            case SELF_DEFINING_CONTENT:

            default:
                throw new BaseExceptions.InvalidState("not implemented: " + _endOfContent);
        }
    }

    private ByteBuffer nonChunkedContent(ByteBuffer in) {
        final long remaining = _contentLength - _contentPosition;
        final int buf_size = in.remaining();

        if (buf_size >= remaining) {
            _contentPosition += remaining;
            ByteBuffer result = submitPartialBuffer(in, (int)remaining);
            shutdownParser();
            return result;
        }
        else {
            _contentPosition += buf_size;
            return submitBuffer(in);
        }
    }

    private ByteBuffer chunkedContent(ByteBuffer in) throws BaseExceptions.BadRequest, BaseExceptions.InvalidState {
        while(true) {
            byte ch;
            sw: switch (_chunkState) {
                case START:
                    _chunkState = ChunkState.CHUNK_SIZE;
                    // Don't want the chunk size and extension field to be too long.
                    resetLimit(256);

                case CHUNK_SIZE:
                    assert _chunkPosition == 0;

                    while (true) {

                        ch = next(in);
                        if (ch == 0) return null;

                        if (HttpTokens.isWhiteSpace(ch) || ch == HttpTokens.SEMI_COLON) {
                            _chunkState = ChunkState.CHUNK_PARAMS;
                            break;  // Break out of the while loop, and fall through to params
                        }
                        else if (ch == HttpTokens.LF) {
                            if (_chunkLength == 0) {
                                _hstate = HeaderState.START;
                                _chunkState = ChunkState.CHUNK_TRAILERS;
                            }
                            else _chunkState = ChunkState.CHUNK;

                            break sw;
                        }
                        else {
                            _chunkLength = 16 * _chunkLength + HttpTokens.hexCharToInt(ch);

                            if (_chunkLength > maxChunkSize) {
                                shutdownParser();
                                throw new BadRequest("Chunk length too large: " + _chunkLength);
                            }
                        }
                    }

                case CHUNK_PARAMS:
                    // Don't store them, for now.
                    for(ch = next(in); ch != HttpTokens.LF; ch = next(in)) {
                        if (ch == 0) return null;
                    }

                    // Check to see if this was the last chunk
                    if (_chunkLength == 0) {
                        _hstate = HeaderState.START;
                        _chunkState = ChunkState.CHUNK_TRAILERS;
                    }
                    else _chunkState = ChunkState.CHUNK;
                    break;

                case CHUNK:
                    final int remaining_chunk_size =  _chunkLength - _chunkPosition;
                    final int chunk_size = in.remaining();

                    if (remaining_chunk_size <= chunk_size) {
                        ByteBuffer result = submitPartialBuffer(in, remaining_chunk_size);
                        _chunkPosition = _chunkLength = 0;
                        _chunkState = ChunkState.CHUNK_LF;
                        return result;
                    }
                    else {
                        _chunkPosition += chunk_size;
                        if (in.remaining() > 0) return submitBuffer(in);
                        else return null;
                    }

                case CHUNK_LF:
                    ch = next(in);
                    if (ch == 0) return null;

                    if (ch != HttpTokens.LF) {
                        shutdownParser();
                        throw new BadRequest("Bad chunked encoding char: '" + (char)ch + "'");
                    }

                    _chunkState = ChunkState.START;
                    break;


                case CHUNK_TRAILERS:    // more headers
                    if (parseHeaders(in)) {
                        // headers complete
                        return emptyBuffer;
                    }
                    else {
                        return null;
                    }
            }
        }
    }

    /** Manages the buffer position while submitting the content -------- */

    private ByteBuffer submitBuffer(ByteBuffer in) {
        ByteBuffer out = in.asReadOnlyBuffer();
        in.position(in.limit());
        return out;
    }

    private ByteBuffer submitPartialBuffer(ByteBuffer in, int size) {
        // Perhaps we are just right? Might be common.
        if (size == in.remaining()) {
            return submitBuffer(in);
        }

        final int old_lim = in.limit();
        final int end = in.position() + size;

        // Make a slice buffer and return its read only image
        in.limit(end);
        ByteBuffer b = in.slice().asReadOnlyBuffer();
        // fast forward our view of the data
        in.limit(old_lim);
        in.position(end);
        return b;
    }

}

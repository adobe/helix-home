# Generate Bookmarklet

<label for="owner">User / Org: </label><input id="owner"><br>
<label for="repo">Repository: </label><input id="repo"><br>
<label for="ref">Branch (optional): </label><input id="ref"><br>
<label for="prefix">URL prefix (optional): </label><input id="prefix"><br>
<br>
<br>
<button onclick="run()">Generate Bookmarklet</button><br>
<br>

<div id="book" style="display:none">
    <p>
        Drag and Drop the image below to you bookmark bar...
    </p>

    <a id="bookmark" title="Helix Preview" href="">
        <img title="Helix Preview" alt="Helix Preview" src="../helix_logo.png" style="height: 32px">
    </a>
</div>

<script>
function run() {
  const owner = document.getElementById('owner').value;
  const repo = document.getElementById('repo').value;
  const ref = document.getElementById('ref').value;
  const pfx = document.getElementById('prefix').value;
  if (!owner || !repo) {
    alert('owner and repo are mandatory.');
    return;
  }

  const url = new URL('https://adobeioruntime.net/api/v1/web/helix/helix-services/content-proxy@1.12.1-lookup-test-tripod');
  url.searchParams.append('owner', owner);
  url.searchParams.append('repo', repo);
  url.searchParams.append('ref', ref || 'master');
  url.searchParams.append('path', '/'); // dummy is needed by content proxy
  if (pfx) {
    url.searchParams.append('prefix', pfx);
  }
  const code = [
    'javascript:(function(){',
    `var u=new URL('${url.href}');`,
    `u.searchParams.append('lookup', window.location.href);`,
    `window.open(u)`,
    '})();',
  ].join('');
  document.getElementById('bookmark').href = code;
  document.getElementById('book').style.display = 'block';
}
</script>


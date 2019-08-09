const jquery = require('jquery');

/**
 * The 'pre' function that is executed before the HTML is rendered
 * @param context The current context of processing pipeline
 * @param context.content The content
 */
function pre(context) {
  const { document } = context.content;
  const $ = jquery(document.defaultView);

  const $sections = $(document.body).children('div');

  // first section has a starting image: add title class and wrap all subsequent items inside a div
  $sections
    .first()
    .has('p:first-child>img')
    .addClass('title')
    .find(':nth-child(1n+2)')
    .wrapAll('<div class="header"></div>');

  // sections consisting of only one image
  $sections
    .filter('[data-hlx-types~="has-only-image"]')
    .not('.title')
    .addClass('image');

  // sections without image and title class gets a default class
  $sections
    .not('.image')
    .not('.title')
    .addClass('default');

  // if there are no sections wrap everything in a default div
  if ($sections.length === 0) {
    $(document.body).children().wrapAll('<div class="default"></div>');
  }

  // construct the tables
  var tables=[];
  var basic={name:'basic', entries: {}};
  let images={name: 'images', entries: {}}

  var titleEl=document.querySelector('h1');
  if (titleEl) {
    basic.entries['title']=titleEl.textContent;
  }

  var descEl=document.querySelector('.title .header p');
  if (descEl) {
    basic.entries['description']=descEl.textContent;
  }

  var imgElNode = document.querySelectorAll('img');
  var imgs = [];
  for (var img of imgElNode) {
    imgs.push(img.src);
  }
  images.entries = {...imgs};
  tables.push(basic);
  tables.push(images);

  context.content.json={tables: tables};
  context.content.json.string=JSON.stringify(context.content.json);
}

module.exports.pre = pre;
/**
 * Override fetch step
 */
module.exports.before = {
  fetch: (context, action) => {
    action.secrets = action.secrets || {};
    action.secrets.HTTP_TIMEOUT = 5000;
  }
}
